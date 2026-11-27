package com.aresstack.askai.research.runtime.browser;

/**
 * Hands out isolated browser user contexts to research workers of ONE chat. Thread-safe: any number of
 * workers may acquire concurrently; when the configured browser capacity is exhausted, {@link #acquire}
 * waits up to the request's (or the pool's) acquire timeout and then fails with
 * {@link BrowserCapacityException} instead of starting more browser processes.
 *
 * <p>The browser capacity is independent of the model capacity: a worker that holds a lease does not hold a
 * model slot and vice versa. {@link #capacity()} is the number of leases the pool hands out at the same time
 * (from the browser settings); how many browser OPERATIONS actually run at once is bounded inside the pool by
 * the number of browser processes it may run.</p>
 */
public interface BrowserUserContextPool {

    /**
     * Acquire a fresh user context (with one browsing context already open) for one worker. The lease must
     * be closed by the caller — try-with-resources or {@code finally} — also on cancel and failure.
     *
     * @throws BrowserCapacityException no capacity within the acquire timeout, or no browser available
     * @throws InterruptedException     the waiting worker was cancelled
     */
    BrowserUserContextLease acquire(BrowserUserContextRequest request)
            throws BrowserCapacityException, InterruptedException;

    /** How many leases may be held at the same time (a settings value; at least 1 when available, else 0). */
    int capacity();

    /**
     * How long a worker waits for browser capacity before the pool fails it (the worker browsers' wait setting, in
     * milliseconds); 0 when the pool never waits. The team's page browser waits as long for a free tab.
     */
    default long acquireTimeoutMillis() {
        return 0L;
    }

    /** A pool without a browser: every acquire fails readably, capacity is 0. */
    static BrowserUserContextPool unavailable(final String reason) {
        return new BrowserUserContextPool() {
            public BrowserUserContextLease acquire(BrowserUserContextRequest request)
                    throws BrowserCapacityException {
                throw new BrowserCapacityException("No browser user contexts available: " + reason);
            }

            public int capacity() {
                return 0;
            }
        };
    }
}
