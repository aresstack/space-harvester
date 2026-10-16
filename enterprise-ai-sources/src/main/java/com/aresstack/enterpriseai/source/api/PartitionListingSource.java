package com.aresstack.enterpriseai.source.api;

/**
 * Optional capability of a source: all resources of one partition ({@link KnowledgeSourcePort#partitions()}, for
 * Confluence a space) as metadata without content, batch by batch. Unlike {@link KnowledgeSourcePort#discover}, which
 * follows links from start points up to a depth and an overall limit for indexing, this lists the partition completely
 * and flat in the order of the source; it is meant for inventories and change detection (title, revision, parent).
 *
 * <p>Callers check with {@code instanceof}. Pass {@code ""} as cursor for the first batch and
 * {@link PartitionResources#nextCursor()} for the following ones until it is empty. All calls block and may hit the
 * network.
 *
 * <p>Cursors are opaque to callers and bound to their partition: a cursor of another partition, of another source or
 * of an older format is rejected with {@code UNSUPPORTED} instead of being silently reinterpreted. Implementations cap
 * {@code limit} at a source-specific batch maximum (Confluence: 100), so a single call never requests a huge response;
 * this limits the batch, never the total number of resources reachable by following the cursor.
 */
public interface PartitionListingSource {

    /**
     * Return one batch of the resources of a partition: at most {@code limit} (and at most the batch maximum of the
     * source), possibly fewer even if more follow.
     *
     * @param partitionKey the key of a partition of this source ({@link SourcePartition#key()})
     * @param cursor       {@code ""} or a cursor this source delivered for the same partition
     * @param limit        the largest batch the caller wants, at least 1; larger values than the batch maximum of the
     *                     source are capped, not rejected
     * @throws KnowledgeSourceException {@code NOT_FOUND} if the partition does not exist (or is not visible),
     *                                  {@code UNSUPPORTED} if the cursor was not delivered for this partition
     */
    PartitionResources resources(String partitionKey, String cursor, int limit) throws KnowledgeSourceException;
}
