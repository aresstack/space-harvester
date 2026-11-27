package com.aresstack.knowledge4j.model.support;

import com.aresstack.knowledge4j.model.ChatListener;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ChatResult;
import com.aresstack.knowledge4j.model.ModelCall;
import com.aresstack.knowledge4j.model.ModelFailure;
import com.aresstack.knowledge4j.model.ModelMessage;
import com.aresstack.knowledge4j.model.ModelSlot;
import com.aresstack.knowledge4j.model.resource.ModelEndpoint;
import com.aresstack.knowledge4j.model.resource.ModelHandle;
import com.aresstack.knowledge4j.model.resource.SlotBinding;
import com.aresstack.knowledge4j.model.resource.SlotRequest;
import com.aresstack.knowledge4j.model.resource.SlotReservation;
import com.aresstack.knowledge4j.model.resource.SlotUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityPlannerTest {

    private static final ModelSlot SLOT = ModelSlot.of("writer");
    private static final ModelHandle MODEL = ModelHandle.of("model-a");
    private static final long WAIT_SECONDS = 5;
    private static final Duration LONG = Duration.ofSeconds(WAIT_SECONDS * 4);

    private final ExecutorService callbacks = Executors.newCachedThreadPool();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stop() {
        callbacks.shutdownNow();
        timer.shutdownNow();
    }

    /** A backend whose calls stay open until the test ends them; counts calls running at once. */
    static final class HeldBackend implements ModelBackend {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final List<ChatListener> open = new CopyOnWriteArrayList<>();
        final AtomicInteger cancelled = new AtomicInteger();

        @Override
        public ModelCall stream(SlotBinding binding, ChatRequest request, ChatListener listener) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            open.add(listener);
            return new ModelCall() {
                @Override
                public void cancel() {
                    cancelled.incrementAndGet();
                    finish(listener);
                }

                @Override
                public boolean isDone() {
                    return !open.contains(listener);
                }
            };
        }

        void finish(ChatListener listener) {
            if (open.remove(listener)) {
                running.decrementAndGet();
                listener.onCompleted(new ChatResult("ok", null, null, ChatResult.FinishReason.STOP, null, "test",
                        Duration.ZERO, "model-a", Duration.ZERO, Duration.ZERO));
            }
        }

        void finishAll() {
            for (ChatListener l : open) {
                finish(l);
            }
        }

        @Override
        public WarmUp warmUp() {
            return WarmUp.REACHABILITY_ONLY;
        }
    }

    static PlannerSettings settings(int capacity, Duration maxWait) {
        SlotBinding binding = new SlotBinding(SLOT, "local", MODEL, 8192, "model-a");
        return new PlannerSettings() {
            @Override
            public List<SlotBinding> bindings() {
                return List.of(binding);
            }

            @Override
            public List<ModelEndpoint> endpoints() {
                return List.of();
            }

            @Override
            public int capacity(String endpointId, ModelHandle model) {
                return capacity;
            }

            @Override
            public Duration maxWait(ModelSlot slot) {
                return maxWait;
            }
        };
    }

    static ChatRequest request() {
        return ChatRequest.builder(SLOT).messages(List.of(ModelMessage.user("Hallo"))).build();
    }

    static final class Recorder implements ChatListener {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger terminals = new AtomicInteger();
        final AtomicReference<ModelFailure> failure = new AtomicReference<>();

        @Override
        public void onCompleted(ChatResult result) {
            terminals.incrementAndGet();
            done.countDown();
        }

        @Override
        public void onFailed(ModelFailure f) {
            terminals.incrementAndGet();
            failure.set(f);
            done.countDown();
        }

        void await() throws InterruptedException {
            assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "a terminal callback arrived");
        }
    }

    @Test
    void neverAdmitsMoreCallsThanTheCapacity() throws Exception {
        HeldBackend backend = new HeldBackend();
        CapacityPlanner planner = new CapacityPlanner(backend, settings(2, LONG), callbacks, timer);
        Recorder[] calls = new Recorder[5];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = new Recorder();
            planner.chat(request(), calls[i]);
        }
        waitFor(() -> backend.running.get() == 2);
        Thread.sleep(100);
        assertEquals(2, backend.running.get(), "the other three wait in the planner, not at the backend");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!allDone(calls) && System.nanoTime() < end) {
            backend.finishAll();
            Thread.sleep(10);
        }
        for (Recorder call : calls) {
            call.await();
            assertEquals(1, call.terminals.get(), "exactly one terminal callback");
        }
        assertEquals(2, backend.peak.get(), "capacity 2 was never exceeded");
        assertEquals(0, planner.resources().capacityOf(SLOT).orElseThrow().getActiveReservations());
    }

    @Test
    void aWaitingReservationTimesOutAfterTheHostsWaitAndLeaksNothing() throws Exception {
        HeldBackend backend = new HeldBackend();
        CapacityPlanner planner = new CapacityPlanner(backend, settings(1, Duration.ofMillis(100)), callbacks,
                timer);
        SlotReservation first = planner.reserve(new SlotRequest(SLOT, 0, LONG, "first")).get(WAIT_SECONDS,
                TimeUnit.SECONDS);
        CompletableFuture<SlotReservation> second = planner.reserve(
                new SlotRequest(SLOT, 0, Duration.ofMillis(100), "second"));
        ExecutionException timedOut = assertThrows(ExecutionException.class,
                () -> second.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(SlotUnavailableException.class, timedOut.getCause());
        assertEquals(SlotUnavailableException.Reason.WAIT_TIMEOUT,
                ((SlotUnavailableException) timedOut.getCause()).getReason());
        first.close();
        SlotReservation third = planner.reserve(new SlotRequest(SLOT, 0, LONG, "third")).get(WAIT_SECONDS,
                TimeUnit.SECONDS);
        assertTrue(third.isActive(), "the place came back");
        third.close();
    }

    @Test
    void cancellingAWaitWithdrawsItAndTheNextWaiterGetsThePlace() throws Exception {
        HeldBackend backend = new HeldBackend();
        CapacityPlanner planner = new CapacityPlanner(backend, settings(1, LONG), callbacks, timer);
        SlotReservation first = planner.reserve(new SlotRequest(SLOT, 0, LONG, "first")).get(WAIT_SECONDS,
                TimeUnit.SECONDS);
        CompletableFuture<SlotReservation> withdrawn = planner.reserve(new SlotRequest(SLOT, 0, LONG, "w"));
        CompletableFuture<SlotReservation> next = planner.reserve(new SlotRequest(SLOT, 0, LONG, "next"));
        withdrawn.cancel(false);
        first.close();
        SlotReservation granted = next.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(granted.isActive());
        assertEquals(1, planner.resources().capacityOf(SLOT).orElseThrow().getActiveReservations());
        granted.close();
        assertEquals(0, planner.resources().capacityOf(SLOT).orElseThrow().getActiveReservations());
    }

    @Test
    void aSecondCallUnderTheSameReservationIsRejected() throws Exception {
        HeldBackend backend = new HeldBackend();
        CapacityPlanner planner = new CapacityPlanner(backend, settings(1, LONG), callbacks, timer);
        SlotReservation reservation = planner.reserve(new SlotRequest(SLOT, 0, LONG, "r")).get(WAIT_SECONDS,
                TimeUnit.SECONDS);
        Recorder first = new Recorder();
        planner.chat(reservation, request(), first);
        Recorder second = new Recorder();
        planner.chat(reservation, request(), second);
        second.await();
        assertEquals(ModelFailure.Kind.RESERVATION_INVALID, second.failure.get().getKind());
        backend.finishAll();
        first.await();
        reservation.close();
    }

    @Test
    void aCallForAnotherSlotOrMoreContextIsRejected() throws Exception {
        HeldBackend backend = new HeldBackend();
        CapacityPlanner planner = new CapacityPlanner(backend, settings(1, LONG), callbacks, timer);
        SlotReservation reservation = planner.reserve(new SlotRequest(SLOT, 4096, LONG, "r")).get(WAIT_SECONDS,
                TimeUnit.SECONDS);
        Recorder bigger = new Recorder();
        planner.chat(reservation, ChatRequest.builder(SLOT).messages(List.of(ModelMessage.user("x")))
                .contextTokens(16384).build(), bigger);
        bigger.await();
        assertEquals(ModelFailure.Kind.RESERVATION_INVALID, bigger.failure.get().getKind());
        assertEquals(0, backend.peak.get(), "nothing reached the backend");
        reservation.close();
    }

    @Test
    void cancellingARunningCallFreesThePlaceOnce() throws Exception {
        HeldBackend backend = new HeldBackend();
        CapacityPlanner planner = new CapacityPlanner(backend, settings(1, LONG), callbacks, timer);
        Recorder recorder = new Recorder();
        ModelCall call = planner.chat(request(), recorder);
        waitFor(() -> backend.running.get() == 1);
        call.cancel();
        call.cancel();
        recorder.await();
        Thread.sleep(50);
        assertEquals(1, recorder.terminals.get());
        waitFor(() -> planner.resources().capacityOf(SLOT).orElseThrow().getActiveReservations() == 0);
        Recorder after = new Recorder();
        planner.chat(request(), after);
        waitFor(() -> backend.running.get() == 1);
        backend.finishAll();
        after.await();
        assertEquals(null, after.failure.get());
    }

    @Test
    void anUnboundSlotFailsWithNoBindingThroughTheListener() throws Exception {
        CapacityPlanner planner = new CapacityPlanner(new HeldBackend(), settings(1, LONG), callbacks, timer);
        Recorder recorder = new Recorder();
        planner.chat(ChatRequest.builder(ModelSlot.of("unknown")).messages(List.of(ModelMessage.user("x")))
                .build(), recorder);
        recorder.await();
        assertEquals(ModelFailure.Kind.NO_BINDING, recorder.failure.get().getKind());
    }

    @Test
    void warmReportsWhatTheBackendReallyDoes() {
        CapacityPlanner planner = new CapacityPlanner(new HeldBackend(), settings(1, LONG), callbacks, timer);
        assertEquals(WarmUp.REACHABILITY_ONLY, planner.warmUp());
        assertFalse(planner.warm(SLOT).isCompletedExceptionally());
    }

    private static boolean allDone(Recorder[] calls) {
        for (Recorder call : calls) {
            if (call.done.getCount() > 0) {
                return false;
            }
        }
        return true;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(10);
        }
    }
}
