package com.aresstack.askai.research.search.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WebSearchResult {

    private final SearchProviderId providerId;
    private final SearchEngine searchEngine;
    private final List<WebSearchHit> hits;
    private final String rawResponse;

    public WebSearchResult(
            SearchProviderId providerId,
            SearchEngine searchEngine,
            List<WebSearchHit> hits,
            String rawResponse) {

        if (providerId == null) {
            throw new IllegalArgumentException(
                    "providerId must not be null");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException(
                    "searchEngine must not be null");
        }
        if (hits == null) {
            throw new IllegalArgumentException(
                    "hits must not be null");
        }

        this.providerId = providerId;
        this.searchEngine = searchEngine;
        this.hits = Collections.unmodifiableList(
                new ArrayList<WebSearchHit>(hits));
        this.rawResponse =
                rawResponse == null ? "" : rawResponse;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public List<WebSearchHit> getHits() {
        return hits;
    }

    public String getRawResponse() {
        return rawResponse;
    }
}
