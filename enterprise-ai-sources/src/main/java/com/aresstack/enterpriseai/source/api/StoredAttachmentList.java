package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The attachment list of a resource, see {@link AttachmentListSource}: metadata of every attachment in the order of
 * the source and the native responses of all list pages, byte for byte. Lists from {@link AttachmentSearchSource}
 * carry the search responses attributed to the resource instead, see there.
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
        this.payloads = copies(payloads);
        this.mediaType = mediaType;
        this.nativeFormat = nativeFormat;
        this.retrievedAt = retrievedAt;
        this.warnings = warnings == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    /**
     * Like {@link #StoredAttachmentList(List, List, String, String, Instant, List)}, but the payloads are responses
     * that answer several lists at once (e.g. one search across many resources, see {@link AttachmentSearchSource}):
     * this list refers to the responses at {@code positions} of {@code shared} instead of copying them again.
     *
     * @param positions 0-based positions in {@code shared}, in the order of this list's payloads
     */
    public StoredAttachmentList(List<KnowledgeResource> attachments, SharedPayloads shared, List<Integer> positions,
                                String mediaType, String nativeFormat, Instant retrievedAt, List<String> warnings) {
        if (attachments == null || shared == null || positions == null || mediaType == null || nativeFormat == null
                || retrievedAt == null) {
            throw new IllegalArgumentException(
                    "attachments, shared, positions, mediaType, nativeFormat and retrievedAt are required");
        }
        this.attachments = Collections.unmodifiableList(new ArrayList<KnowledgeResource>(attachments));
        List<byte[]> selected = new ArrayList<byte[]>(positions.size());
        for (Integer position : positions) {
            if (position == null || position < 0 || position >= shared.payloads.size()) {
                throw new IllegalArgumentException("position outside the shared payloads: " + position);
            }
            selected.add(shared.payloads.get(position));
        }
        this.payloads = selected;
        this.mediaType = mediaType;
        this.nativeFormat = nativeFormat;
        this.retrievedAt = retrievedAt;
        this.warnings = warnings == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    private static List<byte[]> copies(List<byte[]> payloads) {
        List<byte[]> copies = new ArrayList<byte[]>(payloads.size());
        for (byte[] payload : payloads) {
            copies.add(Arrays.copyOf(payload, payload.length));
        }
        return copies;
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

    /**
     * Native responses that answer the lists of several resources at once. Each response is copied once on creation;
     * the lists built from them share these copies, and {@link StoredAttachmentList#payloads()} still hands out copies
     * only, so no caller can change what another list returns.
     */
    public static final class SharedPayloads {

        private final List<byte[]> payloads;

        /** @param payloads the response bodies in request order, byte for byte */
        public SharedPayloads(List<byte[]> payloads) {
            if (payloads == null) {
                throw new IllegalArgumentException("payloads are required");
            }
            this.payloads = Collections.unmodifiableList(copies(payloads));
        }

        /** Number of responses. */
        public int size() {
            return payloads.size();
        }

        @Override
        public String toString() {
            return "SharedPayloads[" + payloads.size() + " responses]";
        }
    }

    @Override
    public String toString() {
        // Never the payloads: they may name protected files.
        return "StoredAttachmentList[" + attachments.size() + " attachments, " + payloads.size() + " responses, "
                + nativeFormat + ", warnings=" + warnings.size() + "]";
    }
}
