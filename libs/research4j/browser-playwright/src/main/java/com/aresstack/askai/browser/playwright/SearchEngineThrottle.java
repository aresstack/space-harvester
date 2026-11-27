package com.aresstack.askai.browser.playwright;

/**
 * The search browser's pace towards the engines (key {@code search-engines}): every engine request, a result page
 * or a redirect hop, keeps a minimum distance to the previous one; a challenge or block pauses the engines with an
 * exponentially growing pause (base, 2×base, 4×base, … up to the cap; both from the settings) that the next normal answer resets. The search
 * browser calls it on its owner thread, so requests from every chat share one pace in arrival order.
 */
final class SearchEngineThrottle {

    /** Time source and sleeper; tests replace it. */
    interface Clock {
        long nowMillis();

        void sleep(long millis) throws InterruptedException;
    }

    static final Clock SYSTEM = new Clock() {
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        public void sleep(long millis) throws InterruptedException {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        }
    };

    private final Clock clock;
    private volatile long minSpacingMillis;
    private volatile long backoffBaseMillis;
    private volatile long backoffCapMillis;

    private long lastRequestAt = Long.MIN_VALUE;
    private long pausedUntil;
    private int consecutiveBlocks;
    private String pauseReason = "";

    SearchEngineThrottle(long minSpacingMillis, long backoffBaseMillis, long backoffCapMillis, Clock clock) {
        this.clock = clock == null ? SYSTEM : clock;
        setMinSpacingMillis(minSpacingMillis);
        setPause(backoffBaseMillis, backoffCapMillis);
    }

    /** First pause after a challenge (0 = no pause) and the longest pause the doubling may reach. */
    void setPause(long baseMillis, long capMillis) {
        this.backoffBaseMillis = Math.max(0L, baseMillis);
        this.backoffCapMillis = Math.max(this.backoffBaseMillis, capMillis);
    }

    void setMinSpacingMillis(long millis) {
        this.minSpacingMillis = Math.max(0L, millis);
    }

    long minSpacingMillis() {
        return minSpacingMillis;
    }

    synchronized boolean isPaused() {
        return clock.nowMillis() < pausedUntil;
    }

    synchronized long pausedUntil() {
        return pausedUntil;
    }

    /** Why the engines are paused, or empty. */
    synchronized String pauseReason() {
        return clock.nowMillis() < pausedUntil ? pauseReason : "";
    }

    /** Wait until the minimum spacing to the previous engine request has passed, then count this request. */
    void awaitSlot() throws InterruptedException {
        long wait;
        synchronized (this) {
            wait = lastRequestAt == Long.MIN_VALUE ? 0L : lastRequestAt + minSpacingMillis - clock.nowMillis();
        }
        if (wait > 0) {
            clock.sleep(wait);
        }
        synchronized (this) {
            lastRequestAt = clock.nowMillis();
        }
    }

    /** The engine answered normally: the next block pauses for the base time again. */
    synchronized void recordSuccess() {
        consecutiveBlocks = 0;
    }

    /** The engine answered with a challenge or block: pause it, twice as long as last time. @return the pause */
    synchronized long recordBlock(String reason) {
        long pause = backoffBaseMillis << Math.min(consecutiveBlocks, 30);
        if (pause < 0 || pause > backoffCapMillis) {
            pause = backoffCapMillis;
        }
        consecutiveBlocks++;
        pausedUntil = clock.nowMillis() + pause;
        pauseReason = reason == null || reason.trim().isEmpty() ? "engine challenge" : reason.trim();
        return pause;
    }
}
