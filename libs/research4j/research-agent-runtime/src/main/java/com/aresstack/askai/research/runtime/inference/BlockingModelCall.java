package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelPort;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The research runtime's synchronous view of the host's {@link ModelPort} (ADR 0001, 3.2). The runtime's
 * model roles are written as blocking calls on their own worker threads; this helper starts one port call,
 * waits for its terminal callback and hands back either the result or the named failure. The port enforces
 * the call's time limit and reports it as {@link ModelFailure.Kind#TIMEOUT}; the wait here only ends early
 * on {@link #cancelAll()} or an interrupt, both of which cancel the call at the provider.
 *
 * <p>One instance may run several calls at once (each on its caller's thread); {@link #cancelAll()} aborts
 * every call in flight, which is what a session close or a pause needs.</p>
 */
public final class BlockingModelCall {

    /** The terminal outcome of one call: exactly one of result and failure is non-null. */
    public static final class Outcome {
        public final ChatResult result;
        public final ModelFailure failure;

        Outcome(ChatResult result, ModelFailure failure) {
            this.result = result;
            this.failure = failure;
        }

        public boolean isOk() {
            return result != null;
        }
    }

    /** Polled while waiting; a true answer cancels the call (e.g. a SERP repair's cancellation signal). */
    public interface CancelCheck {
        boolean isCancelled();
    }

    private static final long POLL_MILLIS = 100L;

    private final ModelPort port;
    private final Set<ModelCall> inFlight = ConcurrentHashMap.newKeySet();
    /**
     * Bumped by every {@link #cancelAll()}: a call started before a cancel but registered after it (so the
     * cancel did not see it) finds a newer generation on registration and cancels itself.
     */
    private final AtomicLong cancelGeneration = new AtomicLong();

    public BlockingModelCall(ModelPort port) {
        if (port == null) {
            throw new IllegalArgumentException("port must not be null");
        }
        this.port = port;
    }

    public Outcome run(ChatRequest request) {
        return run(request, null);
    }

    public Outcome run(ChatRequest request, CancelCheck cancelCheck) {
        final long generation = cancelGeneration.get();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Outcome> outcome = new AtomicReference<Outcome>();
        ModelCall call = port.chat(request, new ChatListener() {
            public void onCompleted(ChatResult result) {
                outcome.compareAndSet(null, new Outcome(result, null));
                done.countDown();
            }

            public void onFailed(ModelFailure failure) {
                outcome.compareAndSet(null, new Outcome(null, failure));
                done.countDown();
            }
        });
        inFlight.add(call);
        if (cancelGeneration.get() != generation) {
            call.cancel(); // a cancelAll() ran between the start and the registration of this call
        }
        try {
            while (!done.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancelCheck != null && cancelCheck.isCancelled()) {
                    call.cancel();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            call.cancel();
            outcome.compareAndSet(null, new Outcome(null,
                    new ModelFailure(ModelFailure.Kind.CANCELLED, "the waiting thread was interrupted")));
        } finally {
            inFlight.remove(call);
        }
        return outcome.get();
    }

    /** Cancels every call in flight; each then ends with {@link ModelFailure.Kind#CANCELLED}. */
    public void cancelAll() {
        cancelGeneration.incrementAndGet();
        for (ModelCall call : inFlight) {
            call.cancel();
        }
    }
}
