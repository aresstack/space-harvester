package com.aresstack.enterpriseai.source.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One batch from {@link PartitionIdListingSource#resourceIds}; immutable. */
public final class PartitionIds {

    private final List<String> localIds;
    private final String nextCursor;

    /**
     * @param localIds   the local IDs of this batch in the order of the source
     * @param nextCursor the cursor of the next batch; {@code null} or empty when this was the last one
     */
    public PartitionIds(List<String> localIds, String nextCursor) {
        this.localIds = localIds == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(localIds));
        this.nextCursor = nextCursor == null ? "" : nextCursor;
    }

    public List<String> localIds() {
        return localIds;
    }

    /** Return the cursor of the next batch; empty when the partition is listed completely. */
    public String nextCursor() {
        return nextCursor;
    }

    public boolean complete() {
        return nextCursor.isEmpty();
    }
}
