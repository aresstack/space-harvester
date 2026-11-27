package com.aresstack.askai.research.domain.search;

/**
 * ONE appearance of a hit in a result set: which provider produced it, in which discovery batch, at which
 * rank, and under which raw URL.
 * <p>
 * A candidate can have several of these. The same normalized URL may show up on batch 1 and batch 3, or be
 * returned by two providers at once — deduplicating those into one candidate must not throw away where each
 * came from, because "everything on the first page is from one source type" is exactly the kind of thing a
 * diverse selection has to be able to see.
 */
public final class SearchOccurrence {

    private final String provider;
    private final int batchOrdinal;
    private final int rank;
    private final String rawUrl;

    public SearchOccurrence(String provider, int batchOrdinal, int rank, String rawUrl) {
        this.provider = provider == null ? "" : provider.trim();
        this.batchOrdinal = Math.max(1, batchOrdinal);
        this.rank = Math.max(0, rank);
        this.rawUrl = rawUrl == null ? "" : rawUrl.trim();
    }

    /** The engine/provider that returned it here. */
    public String getProvider() {
        return provider;
    }

    /** Which discovery batch of the run (1-based); for a browser SERP that is simply the result page. */
    public int getBatchOrdinal() {
        return batchOrdinal;
    }

    /** Position within that batch, as the provider ordered it — never an evaluation. */
    public int getRank() {
        return rank;
    }

    /** The URL exactly as the provider gave it (before normalization/redirect resolution). */
    public String getRawUrl() {
        return rawUrl;
    }

    @Override
    public String toString() {
        return provider + "#" + batchOrdinal + "@" + rank;
    }
}
