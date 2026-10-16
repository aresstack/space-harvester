package com.aresstack.enterpriseai.source.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One batch of children from {@link PageNavigationSource#children}; immutable. */
public final class NavigationChildren {

    private final List<NavigationNode> nodes;
    private final String nextCursor;

    /**
     * @param nodes      the children of this batch in the order of the source
     * @param nextCursor the cursor of the next batch; {@code null} or empty when this was the last one
     */
    public NavigationChildren(List<NavigationNode> nodes, String nextCursor) {
        this.nodes = nodes == null ? Collections.<NavigationNode>emptyList()
                : Collections.unmodifiableList(new ArrayList<NavigationNode>(nodes));
        this.nextCursor = nextCursor == null ? "" : nextCursor;
    }

    public List<NavigationNode> nodes() {
        return nodes;
    }

    /** Return the cursor of the next batch; empty when all children are delivered. */
    public String nextCursor() {
        return nextCursor;
    }

    public boolean complete() {
        return nextCursor.isEmpty();
    }
}
