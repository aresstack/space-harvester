package com.aresstack.knowledge4j.access;

import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.Objects;

/**
 * One source-neutral search hit: a web page, a wiki page, a program or a document alike.
 *
 * @param ref        the source
 * @param title      title for display and ranking; may be empty
 * @param excerpt    short excerpt the search returned; may be empty
 * @param rank       position in the result list, 1-based
 * @param provenance origin and classification of the hit's text
 */
public record SourceHit(SourceRef ref, String title, String excerpt, int rank, Provenance provenance) {

    public SourceHit {
        Objects.requireNonNull(ref, "ref");
        title = title == null ? "" : title;
        excerpt = excerpt == null ? "" : excerpt;
        if (rank < 1) {
            throw new IllegalArgumentException("rank is 1-based");
        }
        provenance = provenance == null ? Provenance.unknown() : provenance;
    }
}
