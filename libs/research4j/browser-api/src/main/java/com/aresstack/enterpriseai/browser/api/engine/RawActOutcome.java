package com.aresstack.enterpriseai.browser.api.engine;

/** What an action did once the page settled: a new document loaded, and/or the document changed. */
public final class RawActOutcome {

    private final boolean navigated;
    private final boolean changed;

    public RawActOutcome(boolean navigated, boolean changed) {
        this.navigated = navigated;
        this.changed = changed;
    }

    public boolean navigated() {
        return navigated;
    }

    public boolean changed() {
        return changed;
    }
}
