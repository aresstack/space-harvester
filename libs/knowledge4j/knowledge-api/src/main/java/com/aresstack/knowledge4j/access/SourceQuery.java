package com.aresstack.knowledge4j.access;

import com.aresstack.knowledge4j.source.Provenance;

/**
 * A search request. The query text is derived content: its provenance says what it was derived from (the task,
 * passages read so far), so the {@link com.aresstack.knowledge4j.policy.TransferPolicy} can keep internal
 * knowledge away from external search services.
 *
 * @param text       the query; never blank
 * @param language   preferred language as an IETF tag, empty for any
 * @param maxHits    how many hits the caller wants at most; a positive setting of the caller
 * @param provenance origin of the query text
 */
public record SourceQuery(String text, String language, int maxHits, Provenance provenance) {

    public SourceQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a query needs text");
        }
        language = language == null ? "" : language;
        if (maxHits < 1) {
            throw new IllegalArgumentException("maxHits must be positive");
        }
        provenance = provenance == null ? Provenance.unknown() : provenance;
    }

    /** Sizes only; never the query text. */
    @Override
    public String toString() {
        return "SourceQuery[" + text.length() + " chars, max " + maxHits + ", " + provenance + "]";
    }
}
