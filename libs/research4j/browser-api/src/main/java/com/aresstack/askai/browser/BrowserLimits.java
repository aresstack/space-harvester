package com.aresstack.askai.browser;

/** Hard resource limits every backend must enforce. Immutable; sensible MVP defaults. */
public final class BrowserLimits {

    private final long maxDownloadBytes;
    private final int maxTextChars;
    private final int maxLinks;
    private final int timeoutMillis;

    public BrowserLimits(long maxDownloadBytes, int maxTextChars, int maxLinks, int timeoutMillis) {
        this.maxDownloadBytes = maxDownloadBytes;
        this.maxTextChars = maxTextChars;
        this.maxLinks = maxLinks;
        this.timeoutMillis = timeoutMillis;
    }

    public static BrowserLimits defaults() {
        return new BrowserLimits(5L * 1024 * 1024, 40_000, 100, 20_000);
    }

    public long getMaxDownloadBytes() {
        return maxDownloadBytes;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public int getMaxLinks() {
        return maxLinks;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }
}
