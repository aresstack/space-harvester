package com.aresstack.knowledge4j.model.support;

import com.aresstack.knowledge4j.model.ChatListener;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ChatResult;
import com.aresstack.knowledge4j.model.ModelCall;
import com.aresstack.knowledge4j.model.ModelFailure;
import com.aresstack.knowledge4j.model.ModelPort;
import com.aresstack.knowledge4j.model.ModelSlot;
import com.aresstack.knowledge4j.model.resource.ModelCapacity;
import com.aresstack.knowledge4j.model.resource.ModelEndpoint;
import com.aresstack.knowledge4j.model.resource.ModelHandle;
import com.aresstack.knowledge4j.model.resource.ResourceSnapshot;
import com.aresstack.knowledge4j.model.resource.SlotBinding;
import com.aresstack.knowledge4j.model.resource.SlotRequest;
import com.aresstack.knowledge4j.model.resource.SlotReservation;
import com.aresstack.knowledge4j.model.resource.SlotUnavailableException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A contract-true {@link ModelPort} over a narrow {@link ModelBackend}, for hosts without their own model
 * management. It keeps one fair queue per model on its endpoint, never admits more calls than the host's
 * capacity, waits as long as the host's setting allows, never loses a place on cancel or failure, rejects a call
 * under a wrong reservation with {@link ModelFailure.Kind#RESERVATION_INVALID}, and delivers every outcome
 * through the listener on a planner thread, never on the caller's thread.
 *
 * <p>{@link #warm} does what the backend's {@link WarmUp} says; {@link #warmUp()} reports it, so a host can show
 * whether "warm" really loaded the model.</p>
 */
public final class CapacityPlanner implements ModelPort, AutoCloseable {

    private final ModelBackend backend;
    private final PlannerSettings settings;
    private final Executor callbacks;
    private final ScheduledExecutorService timer;
    private final Map<String, Pool> pools = new HashMap<>();
    private final Set<PlannedReservation> granted = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param callbacks runs listener callbacks and future completions that do not come from the backend
     * @param timer     ends reservations that waited longer than allowed
     */
    public CapacityPlanner(ModelBackend backend, PlannerSettings settings, Executor callbacks,
                           ScheduledExecutorService timer) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    public WarmUp warmUp() {
        return backend.warmUp();
    }

    @Override
    public CompletableFuture<SlotReservation> reserve(SlotRequest request) {
        CompletableFuture<SlotReservation> future = new CompletableFuture<>();
        if (closed.get()) {
            fail(future, request.getSlot(), SlotUnavailableException.Reason.PORT_CLOSED, "the planner is closed");
            return future;
        }
        Optional<SlotBinding> binding = settings.binding(request.getSlot());
        if (binding.isEmpty()) {
            fail(future, request.getSlot(), SlotUnavailableException.Reason.NO_BINDING, "no model for the slot");
            return future;
        }
        SlotBinding bound = binding.get();
        // The context sent is always the model's own setting; the request names the minimum it needs.
        int context = bound.getContextTokens();
        if (request.getContextTokens() > context) {
            fail(future, request.getSlot(), SlotUnavailableException.Reason.CONTEXT_TOO_LARGE,
                    request.getContextTokens() + " tokens needed, the slot's model has " + context);
            return future;
        }
        Pool pool = pool(bound);
        Waiter waiter = new Waiter(bound, context, future);
        boolean grantNow;
        synchronized (pool) {
            pool.capacity = Math.max(1, settings.capacity(bound.getEndpointId(), bound.getModel()));
            grantNow = pool.waiting.isEmpty() && pool.active < pool.capacity;
            if (grantNow) {
                pool.active++;
            } else {
                pool.waiting.addLast(waiter);
            }
        }
        if (grantNow) {
            hand(pool, waiter);
            return future;
        }
        Duration wait = request.getMaxWait();
        waiter.timeout = timer.schedule(() -> {
            if (withdraw(pool, waiter)) {
                fail(future, request.getSlot(), SlotUnavailableException.Reason.WAIT_TIMEOUT,
                        "no place became free within " + wait);
            }
        }, wait.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((r, e) -> {
            if (future.isCancelled()) {
                withdraw(pool, waiter);
            }
        });
        return future;
    }

    @Override
    public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!(reservation instanceof PlannedReservation)) {
            return failed(listener, ModelFailure.Kind.RESERVATION_INVALID, "not a reservation of this port");
        }
        PlannedReservation planned = (PlannedReservation) reservation;
        if (!planned.isActive()) {
            return failed(listener, ModelFailure.Kind.RESERVATION_INVALID, "the reservation is closed");
        }
        if (!planned.getSlot().equals(request.getSlot())) {
            return failed(listener, ModelFailure.Kind.RESERVATION_INVALID, "the reservation is for another slot");
        }
        if (request.getContextTokens() > planned.getContextTokens()) {
            return failed(listener, ModelFailure.Kind.RESERVATION_INVALID,
                    "the call needs more context than the reservation holds");
        }
        RunningCall running = new RunningCall(planned, listener);
        if (!planned.busy.compareAndSet(null, running)) {
            return failed(listener, ModelFailure.Kind.RESERVATION_INVALID, "another call runs under the reservation");
        }
        try {
            running.delegate.set(backend.stream(planned.binding, request, running));
        } catch (RuntimeException e) {
            running.onFailed(new ModelFailure(ModelFailure.Kind.PROVIDER_ERROR, e.getClass().getSimpleName()));
        }
        if (running.cancelled.get()) {
            running.cancelDelegate();
        }
        return running;
    }

    @Override
    public ModelCall chat(ChatRequest request, ChatListener listener) {
        Objects.requireNonNull(listener, "listener");
        ImplicitCall call = new ImplicitCall(listener);
        Duration wait = settings.maxWait(request.getSlot());
        CompletableFuture<SlotReservation> reservation = reserve(new SlotRequest(request.getSlot(),
                request.getContextTokens(), wait == null ? Duration.ZERO : wait, "implicit"));
        call.reservation = reservation;
        reservation.whenComplete((granted, error) -> {
            if (error != null) {
                if (!reservation.isCancelled()) {
                    call.terminal(new ModelFailure(kindOf(error), detailOf(error)));
                }
                return;
            }
            if (call.cancelled.get()) {
                granted.close();
                return;
            }
            call.inner.set(chat(granted, request, new ChatListener() {
                @Override
                public void onThinkingDelta(String delta) {
                    listener.onThinkingDelta(delta);
                }

                @Override
                public void onContentDelta(String delta) {
                    listener.onContentDelta(delta);
                }

                @Override
                public void onCompleted(ChatResult result) {
                    granted.close();
                    call.done(result);
                }

                @Override
                public void onFailed(ModelFailure failure) {
                    granted.close();
                    call.terminal(failure);
                }
            }));
            if (call.cancelled.get()) {
                call.cancel();
            }
        });
        return call;
    }

    @Override
    public CompletableFuture<Void> warm(ModelSlot slot) {
        Optional<SlotBinding> binding = settings.binding(slot);
        if (binding.isEmpty()) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            fail(failed, slot, SlotUnavailableException.Reason.NO_BINDING, "no model for the slot");
            return failed;
        }
        return backend.warmUp() == WarmUp.NONE ? CompletableFuture.completedFuture(null)
                : backend.warm(binding.get());
    }

    @Override
    public ResourceSnapshot resources() {
        List<SlotBinding> bindings = settings.bindings();
        Map<String, ModelEndpoint> endpoints = new HashMap<>();
        for (ModelEndpoint endpoint : settings.endpoints()) {
            endpoints.put(endpoint.getId(), endpoint);
        }
        List<ModelCapacity> capacities = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SlotBinding binding : bindings) {
            endpoints.computeIfAbsent(binding.getEndpointId(),
                    id -> new ModelEndpoint(id, id, ModelEndpoint.Kind.REMOTE, -1, true));
            String key = key(binding.getEndpointId(), binding.getModel());
            if (!seen.add(key)) {
                continue;
            }
            Pool pool = pool(binding);
            synchronized (pool) {
                int capacity = Math.max(1, settings.capacity(binding.getEndpointId(), binding.getModel()));
                capacities.add(new ModelCapacity(binding.getEndpointId(), binding.getModel(), capacity,
                        pool.active, pool.waiting.size()));
            }
        }
        return new ResourceSnapshot(Instant.now(), new ArrayList<>(endpoints.values()), bindings, capacities,
                Collections.emptyList(), false);
    }

    /** Fails every waiting request and revokes every reservation; calls under them are cancelled. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Waiter> waiting = new ArrayList<>();
        synchronized (pools) {
            for (Pool pool : pools.values()) {
                synchronized (pool) {
                    waiting.addAll(pool.waiting);
                    pool.waiting.clear();
                }
            }
        }
        for (Waiter waiter : waiting) {
            waiter.cancelTimeout();
            fail(waiter.future, waiter.binding.getSlot(), SlotUnavailableException.Reason.PORT_CLOSED,
                    "the planner is closed");
        }
        List<PlannedReservation> active;
        synchronized (granted) {
            active = new ArrayList<>(granted);
        }
        for (PlannedReservation reservation : active) {
            reservation.close();
        }
    }

    // ---- queue ----

    private Pool pool(SlotBinding binding) {
        synchronized (pools) {
            return pools.computeIfAbsent(key(binding.getEndpointId(), binding.getModel()), k -> new Pool());
        }
    }

    private static String key(String endpointId, ModelHandle model) {
        return endpointId + '\u0000' + model.getToken();
    }

    /** Removes a waiter that has not been granted; true when it was still waiting. */
    private boolean withdraw(Pool pool, Waiter waiter) {
        synchronized (pool) {
            boolean removed = pool.waiting.remove(waiter);
            if (removed) {
                waiter.cancelTimeout();
            }
            return removed;
        }
    }

    /** Gives the place already counted in {@code pool.active} to the waiter, or back if the waiter is gone. */
    private void hand(Pool pool, Waiter waiter) {
        waiter.cancelTimeout();
        PlannedReservation reservation = new PlannedReservation("planned-" + ids.incrementAndGet(), waiter.binding,
                waiter.context, pool);
        granted.add(reservation);
        callbacks.execute(() -> {
            if (!waiter.future.complete(reservation)) {
                reservation.close(); // cancelled concurrently: the place is never leaked
            }
        });
    }

    private void release(Pool pool) {
        Waiter next = null;
        synchronized (pool) {
            pool.active--;
            pool.capacity = Math.max(1, pool.capacity);
            if (!pool.waiting.isEmpty() && pool.active < pool.capacity) {
                next = pool.waiting.pollFirst();
                pool.active++;
            }
        }
        if (next != null) {
            hand(pool, next);
        }
    }

    private void fail(CompletableFuture<?> future, ModelSlot slot, SlotUnavailableException.Reason reason,
                      String detail) {
        callbacks.execute(() -> future.completeExceptionally(new SlotUnavailableException(slot, reason, detail)));
    }

    private ModelCall failed(ChatListener listener, ModelFailure.Kind kind, String detail) {
        callbacks.execute(() -> listener.onFailed(new ModelFailure(kind, detail)));
        return Done.INSTANCE;
    }

    private static ModelFailure.Kind kindOf(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof SlotUnavailableException unavailable) {
            switch (unavailable.getReason()) {
                case NO_BINDING:
                    return ModelFailure.Kind.NO_BINDING;
                case ENDPOINT_UNAVAILABLE:
                    return ModelFailure.Kind.ENDPOINT_UNAVAILABLE;
                case CONTEXT_TOO_LARGE:
                    return ModelFailure.Kind.CONTEXT_TOO_LARGE;
                case WAIT_TIMEOUT:
                    return ModelFailure.Kind.CAPACITY_TIMEOUT;
                default:
                    return ModelFailure.Kind.PORT_CLOSED;
            }
        }
        return ModelFailure.Kind.PROVIDER_ERROR;
    }

    private static String detailOf(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static final class Pool {
        int capacity = 1;
        int active;
        final Deque<Waiter> waiting = new ArrayDeque<>();
    }

    private static final class Waiter {
        final SlotBinding binding;
        final int context;
        final CompletableFuture<SlotReservation> future;
        volatile ScheduledFuture<?> timeout;

        Waiter(SlotBinding binding, int context, CompletableFuture<SlotReservation> future) {
            this.binding = binding;
            this.context = context;
            this.future = future;
        }

        void cancelTimeout() {
            ScheduledFuture<?> t = timeout;
            if (t != null) {
                t.cancel(false);
            }
        }
    }

    private final class PlannedReservation implements SlotReservation {
        final String id;
        final SlotBinding binding;
        final int context;
        final Pool pool;
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicReference<RunningCall> busy = new AtomicReference<>();

        PlannedReservation(String id, SlotBinding binding, int context, Pool pool) {
            this.id = id;
            this.binding = binding;
            this.context = context;
            this.pool = pool;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public ModelSlot getSlot() {
            return binding.getSlot();
        }

        @Override
        public String getEndpointId() {
            return binding.getEndpointId();
        }

        @Override
        public ModelHandle getModel() {
            return binding.getModel();
        }

        @Override
        public int getContextTokens() {
            return context;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            granted.remove(this);
            RunningCall running = busy.get();
            if (running != null) {
                running.cancel();
            }
            release(pool);
        }
    }

    /** A call under a reservation: exactly one terminal callback, the reservation is free again after it. */
    private final class RunningCall implements ModelCall, ChatListener {
        final PlannedReservation reservation;
        final ChatListener listener;
        final AtomicReference<ModelCall> delegate = new AtomicReference<>();
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();

        RunningCall(PlannedReservation reservation, ChatListener listener) {
            this.reservation = reservation;
            this.listener = listener;
        }

        @Override
        public void onThinkingDelta(String delta) {
            if (!terminal.get()) {
                listener.onThinkingDelta(delta);
            }
        }

        @Override
        public void onContentDelta(String delta) {
            if (!terminal.get()) {
                listener.onContentDelta(delta);
            }
        }

        @Override
        public void onCompleted(ChatResult result) {
            if (terminal.compareAndSet(false, true)) {
                reservation.busy.compareAndSet(this, null);
                listener.onCompleted(result);
            }
        }

        @Override
        public void onFailed(ModelFailure failure) {
            if (terminal.compareAndSet(false, true)) {
                reservation.busy.compareAndSet(this, null);
                listener.onFailed(failure);
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            cancelDelegate();
            if (terminal.compareAndSet(false, true)) {
                reservation.busy.compareAndSet(this, null);
                callbacks.execute(() -> listener.onFailed(
                        new ModelFailure(ModelFailure.Kind.CANCELLED, "cancelled by the caller")));
            }
        }

        void cancelDelegate() {
            ModelCall call = delegate.get();
            if (call != null) {
                call.cancel();
            }
        }

        @Override
        public boolean isDone() {
            return terminal.get();
        }
    }

    /** A call with an implicit reservation: cancel withdraws the wait or cancels the running call. */
    private final class ImplicitCall implements ModelCall {
        final ChatListener listener;
        final AtomicReference<ModelCall> inner = new AtomicReference<>();
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile CompletableFuture<SlotReservation> reservation;

        ImplicitCall(ChatListener listener) {
            this.listener = listener;
        }

        void done(ChatResult result) {
            if (terminal.compareAndSet(false, true)) {
                listener.onCompleted(result);
            }
        }

        void terminal(ModelFailure failure) {
            if (terminal.compareAndSet(false, true)) {
                listener.onFailed(failure);
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            ModelCall call = inner.get();
            if (call != null) {
                call.cancel();
                return;
            }
            CompletableFuture<SlotReservation> waiting = reservation;
            if (waiting != null) {
                waiting.cancel(false);
            }
            if (terminal.compareAndSet(false, true)) {
                callbacks.execute(() -> listener.onFailed(
                        new ModelFailure(ModelFailure.Kind.CANCELLED, "cancelled while waiting for a place")));
            }
        }

        @Override
        public boolean isDone() {
            return terminal.get();
        }
    }

    private static final class Done implements ModelCall {
        static final Done INSTANCE = new Done();

        @Override
        public void cancel() {
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }
}
