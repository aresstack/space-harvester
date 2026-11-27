package com.aresstack.knowledge4j.passage;

import com.aresstack.knowledge4j.source.Provenance;

/**
 * A passage search: free text, ranked by the host's index (keyword, semantic or both, as the host decides).
 *
 * @param text       the query text; never blank
 * @param maxHits    how many hits the caller wants at most; positive
 * @param provenance origin of the query text
 */
public record PassageQuery(String text, int maxHits, Provenance provenance) {

    public PassageQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a passage query needs text");
        }
        if (maxHits < 1) {
            throw new IllegalArgumentException("maxHits must be positive");
        }
        provenance = provenance == null ? Provenance.unknown() : provenance;
    }

    @Override
    public String toString() {
        return "PassageQuery[" + text.length() + " chars, max " + maxHits + "]";
    }
}
