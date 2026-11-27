package com.aresstack.spaceharvester.browser.runtime;

/**
 * Whether the configured browser can start, checked before a start (driver replacement, driver package, browser
 * executable). The statuses are specific on purpose: a missing browser is not reported like a broken classpath.
 *
 * @param status what the check found
 * @param detail a readable line (channel and executable, or why not)
 */
public record Readiness(Status status, String detail) {

    public enum Status {
        READY,
        /** The playwright4j driver replacement is not the effective Playwright driver (classpath composition broken). */
        INCOMPATIBLE_DRIVER,
        /** The Playwright Core JS driver package (embedded in playwright4j) is not on the classpath. */
        DRIVER_BUNDLE_NOT_FOUND,
        /** No executable for the configured browser (Firefox, Chrome, Edge), or Camoufox is not installed. */
        BROWSER_NOT_INSTALLED
    }

    public boolean isReady() {
        return status == Status.READY;
    }

    /** {@code STATUS: detail}, for logs and status lines. */
    public String render() {
        return status + ": " + detail;
    }
}
