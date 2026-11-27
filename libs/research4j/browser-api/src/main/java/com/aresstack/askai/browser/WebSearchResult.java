package com.aresstack.askai.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured, size-bounded search results (never raw HTML). {@code providerHosts} are the hosts of every
 * search-engine page visited to produce these results (a fallback engine adds a second host) — callers use
 * them to treat the search engines themselves as transit, never as an evidence source.
 */
public final class WebSearchResult {

    private final List<WebSearchItem> items;
    private final List<String> providerHosts;
    private final List<LegacySearchEngineAttemptResult> attempts;

    public WebSearchResult(List<WebSearchItem> items) {
        this(items, Collections.<String>emptyList(),
                Collections.<LegacySearchEngineAttemptResult>emptyList());
    }

    public WebSearchResult(List<WebSearchItem> items, String providerHost) {
        this(items, providerHost == null || providerHost.isEmpty()
                        ? Collections.<String>emptyList() : Collections.singletonList(providerHost),
                Collections.<LegacySearchEngineAttemptResult>emptyList());
    }

    public WebSearchResult(List<WebSearchItem> items, List<String> providerHosts) {
        this(items, providerHosts, Collections.<LegacySearchEngineAttemptResult>emptyList());
    }

    public WebSearchResult(List<WebSearchItem> items, List<String> providerHosts,
                           List<LegacySearchEngineAttemptResult> attempts) {
        this.items = Collections.unmodifiableList(new ArrayList<WebSearchItem>(
                items == null ? Collections.<WebSearchItem>emptyList() : items));
        this.providerHosts = Collections.unmodifiableList(new ArrayList<String>(
                providerHosts == null ? Collections.<String>emptyList() : providerHosts));
        this.attempts = Collections.unmodifiableList(new ArrayList<LegacySearchEngineAttemptResult>(
                attempts == null ? Collections.<LegacySearchEngineAttemptResult>emptyList() : attempts));
    }

    public List<WebSearchItem> getItems() { return items; }

    /** Hosts of all search pages used for this result (empty when unknown). */
    public List<String> getProviderHosts() { return providerHosts; }

    /** First provider host ("" when unknown) — convenience for single-engine callers. */
    public String getProviderHost() { return providerHosts.isEmpty() ? "" : providerHosts.get(0); }

    /** One typed result per attempted engine (diagnostics + fallback-policy input). */
    public List<LegacySearchEngineAttemptResult> getAttempts() { return attempts; }
}
