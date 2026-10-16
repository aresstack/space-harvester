package com.aresstack.enterpriseai.source.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The content references of a page to other pages of the same source ({@link PageNavigationSource#crossReferences}):
 * outgoing ones and, only where the source determines them completely, incoming ones. Structural edges (parent,
 * children) are not part of it. Immutable.
 */
public final class CrossReferences {

    private final List<SourceLink> outgoing;
    private final List<SourceLink> incoming;

    private CrossReferences(List<SourceLink> outgoing, List<SourceLink> incoming) {
        this.outgoing = copy(outgoing);
        this.incoming = incoming == null ? null : copy(incoming);
    }

    /** Create references of a source that cannot tell which pages link here. */
    public static CrossReferences outgoingOnly(List<SourceLink> outgoing) {
        return new CrossReferences(outgoing, null);
    }

    /** Create references with the complete list of incoming references. */
    public static CrossReferences of(List<SourceLink> outgoing, List<SourceLink> incoming) {
        return new CrossReferences(outgoing, incoming == null ? Collections.<SourceLink>emptyList() : incoming);
    }

    /** Return the pages this page links to, each once, in the order of the page. */
    public List<SourceLink> outgoing() {
        return outgoing;
    }

    /** Return whether {@link #incoming()} is complete; otherwise it is empty and means nothing. */
    public boolean incomingSupported() {
        return incoming != null;
    }

    /** Return the pages linking here; empty if not {@link #incomingSupported()}. */
    public List<SourceLink> incoming() {
        return incoming == null ? Collections.<SourceLink>emptyList() : incoming;
    }

    private static List<SourceLink> copy(List<SourceLink> links) {
        return links == null ? Collections.<SourceLink>emptyList()
                : Collections.unmodifiableList(new ArrayList<SourceLink>(links));
    }
}
