package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/**
 * Optional capability of a source: the metadata of a single resource without content, addressed by its local ID in
 * the source (for Confluence the content ID of a page). The resource carries the same fields as in
 * {@link PartitionListingSource#resources}, so a single lookup and a listing of the partition agree.
 *
 * <p>The local ID is independent of the partition: a page moved to another space keeps it. Callers check with
 * {@code instanceof}. All calls block and may hit the network.
 */
public interface ResourceMetadataSource {

    /**
     * The local ID of a resource of this source, as {@link #resourceMetadata} accepts it.
     *
     * @return the local ID or {@code null} if the ID does not belong to this source
     */
    String localId(KnowledgeResourceId resourceId);

    /**
     * The metadata of one resource without content.
     *
     * @param localId the local ID in the source, see {@link #localId}
     * @throws KnowledgeSourceException {@code NOT_FOUND} if there is no such resource or it is not visible,
     *                                  {@code UNSUPPORTED} if the ID has no form this source knows
     */
    KnowledgeResource resourceMetadata(String localId) throws KnowledgeSourceException;
}
