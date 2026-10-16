package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/**
 * Optional capability of a source: the native response of a resource exactly as the source delivered it, for
 * harvesters that keep the original payload (raw first) instead of text extracted from it. Complements
 * {@link KnowledgeSourcePort#load}, which stays unchanged and still delivers indexable text.
 *
 * <p>The payload is the response body after transport decoding, byte for byte, never rebuilt from parsed objects.
 * It contains no request headers, cookies or credentials. Callers check with {@code instanceof}. All calls block and
 * may hit the network.
 */
public interface StoredContentSource {

    /**
     * The local ID of a resource of this source, as {@link #storedContent} accepts it.
     *
     * @return the local ID or {@code null} if the ID does not belong to this source or the resource has no stored
     *         content
     */
    String localId(KnowledgeResourceId resourceId);

    /**
     * The native response of one resource together with the metadata parsed from the same response.
     *
     * @param localId the local ID in the source, see {@link #localId}
     * @throws ResponseTooLargeException if the response exceeds the configured size limit; nothing is truncated
     * @throws KnowledgeSourceException  {@code NOT_FOUND} if there is no such resource or it is not visible,
     *                                   {@code UNSUPPORTED} if the ID has no form this source knows
     */
    StoredContent storedContent(String localId) throws KnowledgeSourceException;
}
