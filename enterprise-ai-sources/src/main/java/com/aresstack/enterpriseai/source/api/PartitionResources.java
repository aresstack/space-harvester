package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One batch from {@link PartitionListingSource#resources}; immutable. */
public final class PartitionResources {

    private final List<KnowledgeResource> resources;
    private final String nextCursor;
    private final String checkpoint;

    /**
     * @param resources  the resources of this batch (metadata only) in the order of the source
     * @param nextCursor the cursor of the next batch; {@code null} or empty when this was the last one
     */
    public PartitionResources(List<KnowledgeResource> resources, String nextCursor) {
        this(resources, nextCursor, "");
    }

    /**
     * @param checkpoint only on the last batch of a change listing ({@link ChangeListingSource}): the exact position
     *                   of the source after everything this listing delivered, or {@code null}/empty when the source
     *                   has none for this pass
     */
    public PartitionResources(List<KnowledgeResource> resources, String nextCursor, String checkpoint) {
        this.resources = resources == null ? Collections.<KnowledgeResource>emptyList()
                : Collections.unmodifiableList(new ArrayList<KnowledgeResource>(resources));
        this.nextCursor = nextCursor == null ? "" : nextCursor;
        this.checkpoint = checkpoint == null || !this.nextCursor.isEmpty() ? "" : checkpoint;
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

    /**
     * Return the position of the source after this change listing (ADR 0042, point 5), empty when there is none. A
     * caller stores it only after it processed every batch successfully and passes it to
     * {@link ChangeListingSource#changedResourcesSince} next time; until then it keeps its previous checkpoint.
     */
    public String checkpoint() {
        return checkpoint;
    }
}
