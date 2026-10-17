package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The attachment list of a resource, see {@link AttachmentListSource}: metadata of every attachment in the order of
 * the source and the native responses of all list pages, byte for byte.
 */
public final class StoredAttachmentList {

    private final List<KnowledgeResource> attachments;
    private final List<byte[]> payloads;
    private final String mediaType;
    private final String nativeFormat;
    private final Instant retrievedAt;
    private final List<String> warnings;

    /**
     * @param attachments  attachment metadata (ID, title, media type, size, revision, parent, location), no bytes
     * @param payloads     the response body of each list page in request order, byte for byte
     * @param mediaType    media type of the payloads, e.g. {@code application/json}
     * @param nativeFormat names the source format and request shape; a different value means payloads are not
     *                     comparable
     * @param retrievedAt  when the source answered the first page
     * @param warnings     entries left out and why, without content or credentials; empty when complete
     */
    public StoredAttachmentList(List<KnowledgeResource> attachments, List<byte[]> payloads, String mediaType,
                                String nativeFormat, Instant retrievedAt, List<String> warnings) {
        if (attachments == null || payloads == null || mediaType == null || nativeFormat == null
                || retrievedAt == null) {
            throw new IllegalArgumentException("attachments, payloads, mediaType, nativeFormat and retrievedAt are required");
        }
        this.attachments = Collections.unmodifiableList(new ArrayList<KnowledgeResource>(attachments));
        List<byte[]> copies = new ArrayList<byte[]>(payloads.size());
        for (byte[] payload : payloads) {
            copies.add(Arrays.copyOf(payload, payload.length));
        }
        this.payloads = copies;
        this.mediaType = mediaType;
        this.nativeFormat = nativeFormat;
        this.retrievedAt = retrievedAt;
        this.warnings = warnings == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public List<KnowledgeResource> attachments() {
        return attachments;
    }

    /** Copies of the response bodies of all list pages in request order. */
    public List<byte[]> payloads() {
        List<byte[]> copies = new ArrayList<byte[]>(payloads.size());
        for (byte[] payload : payloads) {
            copies.add(Arrays.copyOf(payload, payload.length));
        }
        return copies;
    }

    public String mediaType() {
        return mediaType;
    }

    public String nativeFormat() {
        return nativeFormat;
    }

    public Instant retrievedAt() {
        return retrievedAt;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** {@code true} if no entry was left out. */
    public boolean complete() {
        return warnings.isEmpty();
    }

    @Override
    public String toString() {
        // Never the payloads: they may name protected files.
        return "StoredAttachmentList[" + attachments.size() + " attachments, " + payloads.size() + " responses, "
                + nativeFormat + ", warnings=" + warnings.size() + "]";
    }
}
