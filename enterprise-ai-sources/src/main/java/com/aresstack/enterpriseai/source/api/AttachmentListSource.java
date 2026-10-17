package com.aresstack.enterpriseai.source.api;

/**
 * Optional capability of a source: the complete attachment list of a resource as metadata, including attachments the
 * body does not embed, together with the native list responses byte for byte (raw first). It never loads attachment
 * bytes. Callers check with {@code instanceof}. All calls block and may hit the network.
 */
public interface AttachmentListSource {

    /**
     * All attachments of one resource. Pages through the whole list; a list the source cannot deliver completely
     * fails instead of coming back shorter. Entries the source delivers without a usable ID are left out and reported
     * by position in {@link StoredAttachmentList#warnings()}; the warnings never contain file names.
     *
     * @param localId the local ID in the source, as {@link StoredContentSource#localId} returns it
     * @throws ResponseTooLargeException if one response exceeds the configured size limit; nothing is truncated
     * @throws KnowledgeSourceException  {@code NOT_FOUND} if there is no such resource or it is not visible,
     *                                   {@code UNSUPPORTED} if the ID has no form this source knows,
     *                                   {@code INVALID_RESPONSE} if the list has more than {@link #MAX_ATTACHMENTS} entries
     *                                   (usable or not) or its
     *                                   pagination repeats itself
     */
    StoredAttachmentList storedAttachments(String localId) throws KnowledgeSourceException;

    /** Upper bound of one attachment list; a longer one fails instead of being cut. */
    int MAX_ATTACHMENTS = 10000;
}
