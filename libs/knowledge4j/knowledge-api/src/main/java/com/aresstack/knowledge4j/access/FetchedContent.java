package com.aresstack.knowledge4j.access;

import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The content of one source as text, with metadata and references to linked or child sources, so research can
 * follow an internal knowledge base the way it follows links between web pages.
 *
 * @param ref        the source as read, with its revision
 * @param text       the readable text; may be empty
 * @param metadata   host-defined metadata (author, modified, space, library); never content of other sources
 * @param links      linked or child sources in reading order
 * @param provenance origin and classification of the text
 */
public record FetchedContent(SourceRef ref, String text, Map<String, String> metadata, List<SourceRef> links,
                             Provenance provenance) {

    public FetchedContent {
        Objects.requireNonNull(ref, "ref");
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        links = links == null ? List.of() : List.copyOf(links);
        provenance = provenance == null ? Provenance.unknown() : provenance;
    }

    /** Sizes only; never the text. */
    @Override
    public String toString() {
        return "FetchedContent[" + ref.kind() + ", " + text.length() + " chars, " + links.size() + " links, "
                + provenance + "]";
    }
}
