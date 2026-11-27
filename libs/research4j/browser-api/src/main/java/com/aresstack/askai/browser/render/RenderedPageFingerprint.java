package com.aresstack.askai.browser.render;

/**
 * A cheap DOM-state fingerprint (element count, text volume, shape sample) taken before and after
 * the capture pass: differing fingerprints mean the DOM changed DURING capture — the capture is
 * then retried or marked inconsistent, never silently mixed from two states.
 */
public final class RenderedPageFingerprint {

    public final String value;

    public RenderedPageFingerprint(String value) {
        this.value = value == null ? "" : value;
    }

    public boolean matches(RenderedPageFingerprint other) {
        return other != null && value.equals(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
