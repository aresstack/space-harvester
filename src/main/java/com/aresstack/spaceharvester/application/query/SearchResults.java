package com.aresstack.spaceharvester.application.query;

import java.util.List;

/**
 * Ein Stapel Suchtreffer.
 *
 * @param hits       sichtbare Treffer in der Sortierung der Anfrage; kann kürzer als das Limit sein, auch wenn
 *                   {@code complete} noch {@code false} ist
 * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn {@code complete}
 * @param complete   {@code true}, wenn alle Treffer der Indexgeneration gelesen sind
 */
public record SearchResults(String sourceId, SearchIndexState index, List<SearchHit> hits, String nextCursor,
                            boolean complete) {

    public SearchResults {
        hits = List.copyOf(hits);
    }
}
