package com.aresstack.askai.research.domain;

/**
 * DISCOVERY data from a search (SERP title, snippet, rank): usable for reranking, topic hints and query
 * expansion — NEVER citable evidence. A snippet can be shortened, stale or stitched from another part of
 * the page; citable evidence always requires the opened page persisted as a {@link SourceCapture}.
 */
public final class SearchObservation {

    private final String observationId;
    private final String query;
    private final String title;
    private final String snippet;
    private final String url;
    private final String provider;
    private final int rank;
    private final long capturedAtMillis;

    public SearchObservation(String observationId, String query, String title, String snippet,
                             String url, String provider, int rank, long capturedAtMillis) {
        this.observationId = observationId == null ? "" : observationId;
        this.query = query == null ? "" : query;
        this.title = title == null ? "" : title;
        this.snippet = snippet == null ? "" : snippet;
        this.url = url == null ? "" : url;
        this.provider = provider == null ? "" : provider;
        this.rank = rank;
        this.capturedAtMillis = capturedAtMillis;
    }

    public String getObservationId() {
        return observationId;
    }

    public String getQuery() {
        return query;
    }

    public String getTitle() {
        return title;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getUrl() {
        return url;
    }

    public String getProvider() {
        return provider;
    }

    public int getRank() {
        return rank;
    }

    public long getCapturedAtMillis() {
        return capturedAtMillis;
    }
}
