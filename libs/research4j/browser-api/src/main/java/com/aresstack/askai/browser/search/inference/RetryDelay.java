package com.aresstack.askai.browser.search.inference;

/**
 * A neutral, injectable backoff wait for repair retries — so the resolver never hardcodes
 * {@code Thread.sleep} and tests stay deterministic. The production runtime supplies a real
 * cancellation-aware sleeper; tests supply {@link #IMMEDIATE}, which never waits.
 */
public interface RetryDelay {

    RetryDelay IMMEDIATE = new RetryDelay() {
        public RetryDelayResult await(long delayMillis, CancellationSignal cancellationSignal) {
            return cancellationSignal != null && cancellationSignal.isCancelled()
                    ? RetryDelayResult.CANCELLED : RetryDelayResult.COMPLETED;
        }
    };

    RetryDelayResult await(long delayMillis, CancellationSignal cancellationSignal);
}
