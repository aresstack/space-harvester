package com.aresstack.askai.research.runtime.search.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The typed result of ONE provider call: the provider identity, the engine actually queried, and the
 * organic {@link SearchHit}s in provider order. An empty hit list is a valid, successful outcome (a query
 * that found nothing) — it is NEVER a technical failure; failures surface as typed
 * {@link SearchProviderException}s.
 */
public final class SearchProviderResult {

    private final SearchProviderId providerId;
    private final SearchEngine searchEngine;
    private final List<SearchHit> hits;
    private final String rawResponse;
    private final List<String> unresolvedRedirects;

    public SearchProviderResult(SearchProviderId providerId, SearchEngine searchEngine,
                                List<SearchHit> hits) {
        this(providerId, searchEngine, hits, "");
    }

    /**
     * @param rawResponse the provider's raw response body as delivered by Serp4J (sensitive headers removed);
     *                    {@code null} or empty when the provider has none
     */
    public SearchProviderResult(SearchProviderId providerId, SearchEngine searchEngine,
                                List<SearchHit> hits, String rawResponse) {
        this(providerId, searchEngine, hits, rawResponse, Collections.<String>emptyList());
    }

    /**
     * @param unresolvedRedirects one diagnostic line per hit whose redirect URL (e.g. Google {@code /goto}) could
     *                            not be resolved; such hits ARE in {@code hits}, marked UNRESOLVED (askai #41)
     */
    public SearchProviderResult(SearchProviderId providerId, SearchEngine searchEngine,
                                List<SearchHit> hits, String rawResponse, List<String> unresolvedRedirects) {
        if (providerId == null) {
            throw new IllegalArgumentException("Provider id must not be null");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException("Search engine must not be null");
        }
        this.providerId = providerId;
        this.searchEngine = searchEngine;
        this.hits = Collections.unmodifiableList(new ArrayList<SearchHit>(hits));
        this.rawResponse = rawResponse == null ? "" : rawResponse;
        this.unresolvedRedirects = unresolvedRedirects == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(unresolvedRedirects));
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public List<SearchHit> getHits() {
        return hits;
    }

    /**
     * Diagnostics for hits the provider returned whose redirect target is unknown. Non-empty with empty
     * {@link #getHits()} means "hits found, but no target resolvable" — not "no hits".
     */
    public List<String> getUnresolvedRedirects() {
        return unresolvedRedirects;
    }

    /** The provider's raw response body, or an empty string. */
    public String getRawResponse() {
        return rawResponse;
    }
}
