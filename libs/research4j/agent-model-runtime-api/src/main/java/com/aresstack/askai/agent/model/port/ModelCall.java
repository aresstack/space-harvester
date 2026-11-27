package com.aresstack.askai.agent.model.port;

/**
 * Handle of one running chat call, returned immediately by {@link ModelPort#chat}.
 */
public interface ModelCall {

    /**
     * Best-effort abort; idempotent. Stops the generation at the provider (freeing its capacity) and ends
     * the call with a {@link ModelFailure.Kind#CANCELLED} failure unless it had already ended. Cancelling a
     * call does not close the reservation it ran under.
     */
    void cancel();

    /** True once the terminal callback has been delivered. */
    boolean isDone();
}
