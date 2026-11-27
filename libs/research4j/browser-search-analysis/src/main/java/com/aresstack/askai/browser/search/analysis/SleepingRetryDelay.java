package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.inference.RetryDelayResult;

/**
 * The productive {@link RetryDelay}: waits the requested backoff in small, cancellation-checked
 * chunks so a cancelled run stops promptly. Used only when a real backoff is desired; tests inject
 * {@link RetryDelay#IMMEDIATE} instead.
 */
public final class SleepingRetryDelay implements RetryDelay {

    private static final long CHUNK_MILLIS = 50;

    public RetryDelayResult await(long delayMillis, CancellationSignal cancellationSignal) {
        if (cancellationSignal != null && cancellationSignal.isCancelled()) {
            return RetryDelayResult.CANCELLED;
        }
        long slept = 0;
        while (slept < delayMillis) {
            if (cancellationSignal != null && cancellationSignal.isCancelled()) {
                return RetryDelayResult.CANCELLED;
            }
            try {
                Thread.sleep(Math.min(CHUNK_MILLIS, delayMillis - slept));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RetryDelayResult.CANCELLED;
            }
            slept += CHUNK_MILLIS;
        }
        return RetryDelayResult.COMPLETED;
    }
}
