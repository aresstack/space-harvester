package com.aresstack.askai.research.runtime.search;

/**
 * A request for ONE batch of results. It is {@link InitialSearchRequest} plus the provider's own
 * continuation — the only addition traversal needs.
 * <p>
 * The continuation is OPAQUE on purpose: a browser engine will read it as a page number, an API provider as
 * an offset or a cursor token. Only the provider that issued it interprets it, so the acquisition engine
 * never has to learn what "page 2" means for DataForSEO.
 */
public final class DiscoveryRequest {

    private final String query;
    private final int requestedResultCount;
    private final String language;
    private final String country;
    private final String continuation;

    public DiscoveryRequest(String query, int requestedResultCount, String language, String country,
                            String continuation) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query must not be empty");
        }
        if (requestedResultCount <= 0) {
            throw new IllegalArgumentException("Requested result count must be positive");
        }
        this.query = query.trim();
        this.requestedResultCount = requestedResultCount;
        this.language = language;
        this.country = country;
        this.continuation = continuation == null ? "" : continuation.trim();
    }

    /** The first batch of a run: no continuation yet. */
    public static DiscoveryRequest first(String query, int requestedResultCount, String language,
                                         String country) {
        return new DiscoveryRequest(query, requestedResultCount, language, country, "");
    }

    /** The next batch, using what the previous one handed back. */
    public DiscoveryRequest next(String nextContinuation) {
        return new DiscoveryRequest(query, requestedResultCount, language, country, nextContinuation);
    }

    public String getQuery() {
        return query;
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

    /** The provider's own "where to continue" token; "" for the first batch. */
    public String getContinuation() {
        return continuation;
    }

    public boolean isFirstBatch() {
        return continuation.isEmpty();
    }

    /** The legacy single-batch request, for strategies that cannot paginate. */
    public InitialSearchRequest toInitialSearchRequest() {
        return new InitialSearchRequest(query, requestedResultCount, language, country);
    }
}
