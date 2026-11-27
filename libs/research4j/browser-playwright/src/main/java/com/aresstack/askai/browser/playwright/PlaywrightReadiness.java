package com.aresstack.askai.browser.playwright;

/**
 * Structured result of the capability probing at browser runtime start. Statuses are SPECIFIC on purpose — a missing
 * browser is not reported the same way as a broken classpath. Note there is no NODE_RUNTIME_NOT_FOUND status:
 * playwright4j's whole point is that no Node.js runtime exists; its equivalent failure mode is a missing
 * driver-bundle (the Playwright Core JS package on the classpath), reported as DRIVER_BUNDLE_NOT_FOUND.
 */
final class PlaywrightReadiness {

    enum Status {
        READY,
        /** The playwright4j Driver replacement is not the effective Driver (classpath composition broken). */
        INCOMPATIBLE_DRIVER,
        /** The Playwright Core JS driver package (com.microsoft.playwright:driver-bundle) is not on the classpath. */
        DRIVER_BUNDLE_NOT_FOUND,
        /** No executable found for the configured browser channel (chrome/msedge). */
        BROWSER_NOT_INSTALLED,
        /** The browser executable exists but launching it failed. */
        BROWSER_START_FAILED
    }

    private final Status status;
    private final String detail;

    PlaywrightReadiness(Status status, String detail) {
        this.status = status;
        this.detail = detail == null ? "" : detail;
    }

    Status getStatus() {
        return status;
    }

    String getDetail() {
        return detail;
    }

    boolean isReady() {
        return status == Status.READY;
    }

    /** Readable single line for tool errors and STDERR logs (never a stack trace). */
    String render() {
        return detail.isEmpty() ? status.name() : status.name() + ": " + detail;
    }
}
