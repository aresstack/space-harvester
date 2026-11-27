package com.aresstack.askai.research.runtime.search;

/**
 * The neutral request for the ONE thing a search strategy is responsible for: turning a query into a list
 * of initial entry-URL candidates. It carries no Swing types and no provider DTOs — the same request is
 * served identically by the legacy browser SERP path and by any API provider. {@code language}/{@code
 * country} are optional hints (a provider may fall back to its own configured locale).
 */
public final class InitialSearchRequest {

    private final String query;
    private final int requestedResultCount;
    private final String language;
    private final String country;

    public InitialSearchRequest(String query, int requestedResultCount, String language, String country) {
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
    }

    public String getQuery() {
        return query;
    }

    public int getRequestedResultCount() {
        return requestedResultCount;
    }

    /** Optional ISO language hint (may be {@code null}); a provider may use its configured default. */
    public String getLanguage() {
        return language;
    }

    /** Optional ISO country hint (may be {@code null}); a provider may use its configured default. */
    public String getCountry() {
        return country;
    }
}
