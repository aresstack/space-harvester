package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.playwright.SearchBrowser;
import com.aresstack.askai.research.runtime.acquire.OpaqueRedirectResolver;

/**
 * {@link OpaqueRedirectResolver} over AskAI's search browser: the engine's redirect hop runs in the one browser that
 * talks to the engine (same cookies, same identity, serialised on its owner thread), the research browser never
 * opens an engine address.
 */
public final class SearchBrowserRedirectResolver implements OpaqueRedirectResolver {

    /** The longest one hop may take; a slow engine is not worth a stalled search. */
    public static final long HOP_TIMEOUT_MILLIS = 15_000L;

    private final SearchBrowser searchBrowser;

    public SearchBrowserRedirectResolver(SearchBrowser searchBrowser) {
        this.searchBrowser = searchBrowser;
    }

    @Override
    public Resolution resolve(String wrapperUrl) {
        if (searchBrowser == null) {
            return Resolution.unresolved("no search browser");
        }
        SearchBrowser.RedirectResolution hop;
        try {
            hop = searchBrowser.resolveRedirect(wrapperUrl, HOP_TIMEOUT_MILLIS);
        } catch (RuntimeException e) {
            return Resolution.unresolved(e.getClass().getSimpleName());
        }
        switch (hop.status) {
            case RESOLVED:
                return Resolution.resolved(hop.targetUrl);
            case BLOCKED:
                return Resolution.blocked(hop.detail);
            default:
                return Resolution.unresolved(hop.detail);
        }
    }
}
