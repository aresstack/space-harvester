package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One batch from {@link PartitionListingSource#resources}; immutable. */
public final class PartitionResources {

    private final List<KnowledgeResource> resources;
    private final String nextCursor;

    /**
     * @param resources  the resources of this batch (metadata only) in the order of the source
     * @param nextCursor the cursor of the next batch; {@code null} or empty when this was the last one
     */
    public PartitionResources(List<KnowledgeResource> resources, String nextCursor) {
        this.resources = resources == null ? Collections.<KnowledgeResource>emptyList()
                : Collections.unmodifiableList(new ArrayList<KnowledgeResource>(resources));
        this.nextCursor = nextCursor == null ? "" : nextCursor;
    }

    public List<KnowledgeResource> resources() {
        return resources;
    }

    /** Return the cursor of the next batch; empty when the partition is listed completely. */
    public String nextCursor() {
        return nextCursor;
    }

    public boolean complete() {
        return nextCursor.isEmpty();
    }
}
