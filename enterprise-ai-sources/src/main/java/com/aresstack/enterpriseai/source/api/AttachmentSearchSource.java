package com.aresstack.enterpriseai.source.api;

import java.util.Collection;
import java.util.Map;

/**
 * Optional capability of a source: the attachment lists of many resources from a few searches instead of one list
 * request per resource ({@link AttachmentListSource}), as metadata together with the native search responses byte for
 * byte (raw first). It never loads attachment bytes. Callers check with {@code instanceof}. All calls block and may
 * hit the network.
 *
 * <p>The rules of {@link AttachmentListSource} apply to every list: nothing is cut, a list of one resource with more
 * than {@link AttachmentListSource#MAX_ATTACHMENTS} entries (usable or not) or a repeating pagination fails, one
 * response over the configured size limit is a {@link ResponseTooLargeException}, and entries without a usable ID are
 * left out and reported by position in {@link StoredAttachmentList#warnings()}, never by file name. In addition, one
 * call reads at most {@link #MAX_TOTAL_ATTACHMENTS} entries over all its searches and fails above that.
 *
 * <p><b>Raw responses.</b> One search response usually answers several resources. Each list carries, in request
 * order, those responses of its search that contain at least one of its entries (usable or not); positions in its
 * warnings count within these payloads. A requested resource without any entry carries all responses of the search
 * that covered it, which together show that nothing was found. The lists of one call share these responses in memory
 * ({@link StoredAttachmentList.SharedPayloads}); {@link StoredAttachmentList#payloads()} of different lists may thus
 * return equal bytes, which callers storing raw data can keep once. The {@link StoredAttachmentList#nativeFormat()}
 * names the search request shape and differs from that of {@link AttachmentListSource}.
 *
 * <p><b>Visibility.</b> A search only returns what the account may see. A resource that does not exist or is not
 * visible is indistinguishable from one without attachments, and nothing reveals how many entries were hidden.
 */
public interface AttachmentSearchSource {

    /**
     * The attachment lists of the given resources: exactly one list per distinct requested ID, in the order of the
     * first occurrence; an empty, complete list for a resource without attachments, and also for one that does not
     * exist or is not visible (unlike {@link AttachmentListSource#storedAttachments}, which reports that as
     * {@code NOT_FOUND}). An empty collection costs no request.
     *
     * @param localIds local IDs in the source, as {@link StoredContentSource#localId} returns them
     * @return unmodifiable map from local ID to its list
     * @throws ResponseTooLargeException if one response exceeds the configured size limit; nothing is truncated
     * @throws KnowledgeSourceException  {@code UNSUPPORTED} before any request if one ID has no form this source
     *                                   knows, {@code INVALID_RESPONSE} if one list has more than
     *                                   {@link AttachmentListSource#MAX_ATTACHMENTS} entries, the call more than
     *                                   {@link #MAX_TOTAL_ATTACHMENTS}, or a pagination repeats itself
     */
    Map<String, StoredAttachmentList> storedAttachmentsOf(Collection<String> localIds) throws KnowledgeSourceException;

    /**
     * The attachment lists of all resources of one partition that have at least one attachment the account can see,
     * keyed by the local ID of the resource, in the order the resources first appear in the search. Resources without
     * attachments do not appear, nor do attachments of anything that is not a resource of this source (e.g. a blog
     * post in Confluence).
     *
     * @param partitionKey the key of the partition, as {@link SourcePartition#key()} returns it
     * @return unmodifiable map from local ID to its list
     * @throws ResponseTooLargeException if one response exceeds the configured size limit; nothing is truncated
     * @throws KnowledgeSourceException  {@code NOT_FOUND} if there is no such partition or it is not visible,
     *                                   {@code INVALID_RESPONSE} if one list has more than
     *                                   {@link AttachmentListSource#MAX_ATTACHMENTS} entries, the partition more than
     *                                   {@link #MAX_TOTAL_ATTACHMENTS}, an entry cannot be assigned to a resource, or
     *                                   a pagination repeats itself
     */
    Map<String, StoredAttachmentList> storedAttachmentsInPartition(String partitionKey) throws KnowledgeSourceException;

    /**
     * Upper bound of the entries one call reads over all its searches (usable or not); a larger catalog fails instead
     * of being cut. Callers then ask for fewer resources at a time or fall back to {@link AttachmentListSource}.
     */
    int MAX_TOTAL_ATTACHMENTS = 50000;
}
