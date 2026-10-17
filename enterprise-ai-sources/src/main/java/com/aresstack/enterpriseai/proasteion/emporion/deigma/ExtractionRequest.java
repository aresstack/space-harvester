package com.aresstack.enterpriseai.proasteion.emporion.deigma;

import java.util.Arrays;

/**
 * An immutable request to extract content from a resource.
 *
 * <p>Carries the stable resource id, the raw content,
 * and optional hints to aid content detection and extraction.
 *
 * <p>The byte array is defensively copied on construction and on access
 * to guarantee immutability.
 */
public final class ExtractionRequest {

    private final String resourceId;
    private final byte[] content;
    private final String filenameHint;
    private final String contentTypeHint;
    private final DetectedContentType detectedContentType;

    public ExtractionRequest(String resourceId, byte[] content,
                             String filenameHint, String contentTypeHint) {
        this(resourceId, content, filenameHint, contentTypeHint, null);
    }

    public ExtractionRequest(String resourceId, byte[] content,
                             String filenameHint, String contentTypeHint,
                             DetectedContentType detectedContentType) {
        if (resourceId == null) {
            throw new IllegalArgumentException("Resource id must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("Content must not be null");
        }
        this.resourceId = resourceId;
        this.content = Arrays.copyOf(content, content.length);
        this.filenameHint = filenameHint;
        this.contentTypeHint = contentTypeHint;
        this.detectedContentType = detectedContentType;
    }

    /** Returns the stable id of the resource (source-specific, e.g. the file path). */
    public String resourceId() {
        return resourceId;
    }

    /** Returns a defensive copy of the raw content bytes. */
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    /** Returns the optional filename hint, or {@code null}. */
    public String filenameHint() {
        return filenameHint;
    }

    /** Returns the optional content type hint, or {@code null}. */
    public String contentTypeHint() {
        return contentTypeHint;
    }

    /**
     * Returns the detected content type passed from the detection phase,
     * or {@code null} if not provided. Extractors should prefer this over
     * creating their own hardcoded type when present.
     */
    public DetectedContentType detectedContentType() {
        return detectedContentType;
    }
}
