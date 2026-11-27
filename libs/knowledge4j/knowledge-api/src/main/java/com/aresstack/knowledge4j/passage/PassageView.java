package com.aresstack.knowledge4j.passage;

import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.Objects;

/**
 * A passage as the libraries read it: a stretch of text of one source with its exact location. The host keeps
 * its own passage model and maps it to this view.
 *
 * @param id          stable passage id within the host's knowledge state
 * @param source      the source and location of the passage
 * @param text        the passage text
 * @param headingPath headings above the passage, separated by " > ", empty when unknown
 * @param language    language of the text as an IETF tag, empty when unknown
 * @param provenance  origin and classification of the text
 */
public record PassageView(String id, SourceRef source, String text, String headingPath, String language,
                          Provenance provenance) {

    public PassageView {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a passage needs an id");
        }
        Objects.requireNonNull(source, "source");
        text = text == null ? "" : text;
        headingPath = headingPath == null ? "" : headingPath;
        language = language == null ? "" : language;
        provenance = provenance == null ? Provenance.unknown() : provenance;
    }

    /** Sizes only; never the text. */
    @Override
    public String toString() {
        return "PassageView[" + id + ", " + text.length() + " chars, " + provenance + "]";
    }
}
