package com.aresstack.askai.research.runtime.search.provider;

/**
 * The typed request handed to a single {@link SearchProvider}. Unlike the neutral
 * {@link com.aresstack.askai.research.runtime.search.InitialSearchRequest}, it names the concrete
 * {@link SearchEngine} the strategy chose, so a multi-engine provider (Bright Data, DataForSEO) knows which
 * index to query. {@code language}/{@code country} are optional overrides for the provider's configured
 * defaults.
 */
public final class SearchProviderRequest {

    private final String query;
    private final SearchEngine searchEngine;
    private final int requestedResultCount;
    private final String language;
    private final String country;
    private final int offset;

    public SearchProviderRequest(String query, SearchEngine searchEngine, int requestedResultCount,
                                 String language, String country) {
        this(query, searchEngine, requestedResultCount, language, country, 0);
    }

    /** @param offset results to skip (result page n starts at n * page size); 0 = first page */
    public SearchProviderRequest(String query, SearchEngine searchEngine, int requestedResultCount,
                                 String language, String country, int offset) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query must not be empty");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException("Search engine must not be null");
        }
        if (requestedResultCount <= 0) {
            throw new IllegalArgumentException("Requested result count must be positive");
        }
        this.query = query.trim();
        this.searchEngine = searchEngine;
        this.requestedResultCount = requestedResultCount;
        this.language = language;
        this.country = country;
        this.offset = Math.max(0, offset);
    }

    public int getOffset() {
        return offset;
    }

    public String getQuery() {
        return query;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public int getRequestedResultCount() {
        return requestedResultCount;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }
}
