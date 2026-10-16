package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;

/**
 * The native response of a resource, see {@link StoredContentSource}.
 *
 * <p>{@link #resource()} is parsed from exactly these bytes, so its revision belongs to this payload.
 */
public final class StoredContent {

    private final KnowledgeResource resource;
    private final byte[] payload;
    private final String mediaType;
    private final String nativeFormat;
    private final Instant retrievedAt;
    private final String sha256;

    /**
     * @param resource     metadata parsed from the payload (ID, revision, parent, scope, attributes)
     * @param payload      the response body, byte for byte
     * @param mediaType    the media type of the payload, e.g. {@code application/json}
     * @param nativeFormat names the source format and request shape, e.g. which fields were expanded; a different
     *                     value means payloads are not comparable
     * @param retrievedAt  when the source answered
     */
    public StoredContent(KnowledgeResource resource, byte[] payload, String mediaType, String nativeFormat,
                         Instant retrievedAt) {
        if (resource == null || payload == null || mediaType == null || nativeFormat == null || retrievedAt == null) {
            throw new IllegalArgumentException("resource, payload, mediaType, nativeFormat and retrievedAt are required");
        }
        this.resource = resource;
        this.payload = Arrays.copyOf(payload, payload.length);
        this.mediaType = mediaType;
        this.nativeFormat = nativeFormat;
        this.retrievedAt = retrievedAt;
        this.sha256 = sha256(this.payload);
    }

    public KnowledgeResource resource() {
        return resource;
    }

    /** A copy of the payload bytes. */
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public int length() {
        return payload.length;
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

    /** SHA-256 of the payload bytes as lowercase hex; proves the payload unchanged, not that the content changed. */
    public String sha256() {
        return sha256;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        // Never the payload: it may hold protected content.
        return "StoredContent[" + resource.id() + ", " + payload.length + " bytes, " + nativeFormat + ", sha256="
                + sha256 + "]";
    }
}
