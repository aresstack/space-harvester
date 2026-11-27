package com.aresstack.askai.research.domain.search;

/**
 * ONE portion of results a provider handed over, plus how to ask it for the next one.
 * <p>
 * Deliberately not called a "SERP page": that is only true for a browser engine. An API provider works with
 * a depth parameter, an offset or an opaque cursor, and forcing it to pretend it has pages would either lie
 * or lose the continuation it actually needs. For a browser provider the mapping is simply
 * {@code batch 1 = result page 1}.
 */
public final class DiscoveryBatch {

    private final int ordinal;
    private final String provider;
    private final int resultCount;
    private final String continuation;

    public DiscoveryBatch(int ordinal, String provider, int resultCount, String continuation) {
        this.ordinal = Math.max(1, ordinal);
        this.provider = provider == null ? "" : provider.trim();
        this.resultCount = Math.max(0, resultCount);
        this.continuation = continuation == null ? "" : continuation.trim();
    }

    /** 1-based position in the run; for a browser engine this is the result page number. */
    public int getOrdinal() {
        return ordinal;
    }

    public String getProvider() {
        return provider;
    }

    /** How many results this batch contained (before deduplication into candidates). */
    public int getResultCount() {
        return resultCount;
    }

    /**
     * What the provider needs to deliver the NEXT batch — a page number, an offset, a cursor token; opaque
     * to everything except that provider. Empty means "no further batch available".
     */
    public String getContinuation() {
        return continuation;
    }

    public boolean hasContinuation() {
        return !continuation.isEmpty();
    }
}
