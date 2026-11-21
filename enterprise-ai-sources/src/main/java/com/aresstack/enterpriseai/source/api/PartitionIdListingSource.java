package com.aresstack.enterpriseai.source.api;

/**
 * Optional capability of a source: the local IDs of all resources of one partition, without metadata and without
 * content. It is the cheapest complete listing a source offers; a caller compares it with what it knows to find
 * removed resources without listing the partition with metadata again (Confluence: {@code /rest/api/content} without
 * {@code expand}, larger batches than {@link PartitionListingSource}).
 *
 * <p>Callers check with {@code instanceof}. Paging works as in {@link PartitionListingSource}: {@code ""} for the first
 * batch, then {@link PartitionIds#nextCursor()} until it is empty. Cursors are opaque and bound to their partition; a
 * foreign cursor is rejected with {@code UNSUPPORTED}. The IDs are the local IDs of
 * {@link ResourceMetadataSource#localId}. Resources the caller may not see are missing, as in every other listing.
 * All calls block and may hit the network.
 */
public interface PartitionIdListingSource {

    /**
     * Return one batch of the local IDs of the resources of a partition, in the order of the source.
     *
     * @param partitionKey the key of a partition of this source ({@link SourcePartition#key()})
     * @param cursor       {@code ""} or a cursor this method delivered for the same partition
     * @param limit        the largest batch the caller wants, at least 1; capped at the batch maximum of the source
     * @throws KnowledgeSourceException {@code NOT_FOUND} if the partition does not exist (or is not visible),
     *                                  {@code UNSUPPORTED} if the cursor was not delivered for this partition
     */
    PartitionIds resourceIds(String partitionKey, String cursor, int limit) throws KnowledgeSourceException;
}
