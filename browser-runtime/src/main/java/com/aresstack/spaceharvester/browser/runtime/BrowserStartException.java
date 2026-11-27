package com.aresstack.spaceharvester.browser.runtime;

/**
 * A browser did not start. The message is one readable line (channel, stage, elapsed time, first line of the cause,
 * and where the full report was written); the full exception is in the start failure log.
 */
public final class BrowserStartException extends Exception {

    private final String stage;

    public BrowserStartException(String message, String stage, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    /** The step that failed: CAMOUFOX_CHECK, PLAYWRIGHT_CREATE, PROFILE, ADDON_PREPARE, BROWSER_LAUNCH, ADDON_LOAD. */
    public String stage() {
        return stage;
    }
}
