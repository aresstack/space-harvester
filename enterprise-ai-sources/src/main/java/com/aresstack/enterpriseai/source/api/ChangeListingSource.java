package com.aresstack.enterpriseai.source.api;

import java.time.Duration;

/**
 * Optional capability of a source: which resources of one partition changed recently and which were removed, as
 * metadata without content. It complements {@link PartitionListingSource}: after one flat inventory a caller keeps its
 * catalog current with one cheap query for changes instead of listing the partition again (Confluence: one CQL query
 * {@code lastmodified} and one query of the trash).
 *
 * <p>Callers check with {@code instanceof}. Paging works as in {@link PartitionListingSource}: {@code ""} for the first
 * batch, then {@link PartitionResources#nextCursor()} until it is empty. Cursors are opaque, bound to their partition
 * and to the kind of listing; a foreign cursor is rejected with {@code UNSUPPORTED}. A cursor keeps the time window
 * of its first batch: nothing changed before the first batch is lost while paging. A resource changed again while
 * paging may be reported twice or not at all in this pass; a following query from the start of this pass finds it.
 *
 * <p>The time window is relative to the clock of the source, not of the caller: there is no time zone or clock skew
 * to convert. Callers add a small overlap to the time since their last query; a resource reported twice is harmless.
 * All calls block and may hit the network.
 */
public interface ChangeListingSource {

    /**
     * Return one batch of the resources of a partition that were created or modified within the last {@code within},
     * newest change first. Resources the caller may not see are missing, as in every other listing.
     *
     * @param partitionKey the key of a partition of this source ({@link SourcePartition#key()})
     * @param within       how far back to look, positive; sources round it up to their granularity (Confluence:
     *                     minutes). Ignored when {@code cursor} is not empty.
     * @param cursor       {@code ""} or a cursor this method delivered for the same partition
     * @param limit        the largest batch the caller wants, at least 1; capped at the batch maximum of the source
     * @throws KnowledgeSourceException {@code NOT_FOUND} if the partition does not exist (or is not visible),
     *                                  {@code UNSUPPORTED} if the cursor was not delivered for this partition
     */
    PartitionResources changedResources(String partitionKey, Duration within, String cursor, int limit)
            throws KnowledgeSourceException;

    /**
     * Return one batch of the resources of a partition that were removed but are still known to the source
     * (Confluence: pages in the trash of the space). Such a resource is authoritatively deleted for every user; a
     * resource purged from the trash no longer appears here and is found by the next inventory.
     *
     * @param partitionKey the key of a partition of this source
     * @param cursor       {@code ""} or a cursor this method delivered for the same partition
     * @param limit        the largest batch the caller wants, at least 1; capped at the batch maximum of the source
     * @throws KnowledgeSourceException {@code NOT_FOUND} if the partition does not exist (or is not visible),
     *                                  {@code UNSUPPORTED} if the cursor was not delivered for this partition
     */
    PartitionResources removedResources(String partitionKey, String cursor, int limit) throws KnowledgeSourceException;
}
