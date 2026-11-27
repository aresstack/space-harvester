package com.aresstack.enterpriseai.browser.api;

/**
 * The page after {@link ChatBrowser#act}, once it settled: {@code navigated} when a new document was loaded (form
 * submit, redirecting button), {@code changed} when the same document changed visibly (menu opened, results shown by
 * a single page app, content swapped).
 */
public final class BrowserActResult {

    private final BrowserPage page;
    private final boolean navigated;
    private final boolean changed;

    public BrowserActResult(BrowserPage page, boolean navigated, boolean changed) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        this.page = page;
        this.navigated = navigated;
        this.changed = changed;
    }

    public BrowserPage page() {
        return page;
    }

    public boolean navigated() {
        return navigated;
    }

    public boolean changed() {
        return changed;
    }
}
