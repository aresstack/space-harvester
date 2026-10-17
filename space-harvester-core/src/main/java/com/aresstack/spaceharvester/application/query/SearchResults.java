package com.aresstack.spaceharvester.application.query;

import java.util.List;

/**
 * Ein Stapel Suchtreffer.
 *
 * @param sourceId   die angefragte Quelle oder {@code null} bei der globalen Suche (#114); jeder Treffer nennt seine
 *                   Quelle selbst
 * @param index      gelesener Indexstand oder {@code null}, wenn global keine lesbare Quelle im Index liegt
 * @param hits       sichtbare Treffer in der Sortierung der Anfrage; kann kürzer als das Limit sein, auch wenn
 *                   {@code complete} noch {@code false} ist
 * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn {@code complete}; bei {@code live} immer
 *                   {@code null}
 * @param complete   {@code true}, wenn alle Treffer der Indexgeneration gelesen sind; bei {@code live}, wenn keine
 *                   Quelle mehr Treffer hatte als abgefragt
 * @param live       {@code true}, wenn die Treffer aus der Suche der Quellen stammen ({@link ProvisioningSearch});
 *                   ausgeliefert werden sie auch dann aus dem Index, in den sie nachgeladen wurden
 */
public record SearchResults(String sourceId, SearchIndexState index, List<SearchHit> hits, String nextCursor,
                            boolean complete, boolean live) {

    public SearchResults {
        hits = List.copyOf(hits);
    }

    /** Treffer aus dem Index. */
    public SearchResults(String sourceId, SearchIndexState index, List<SearchHit> hits, String nextCursor,
                         boolean complete) {
        this(sourceId, index, hits, nextCursor, complete, false);
    }

    /** Ob die Treffer aus allen freigegebenen Quellen stammen. */
    public boolean global() {
        return sourceId == null;
    }
}
