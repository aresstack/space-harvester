package com.aresstack.enterpriseai.proasteion.emporion.deigma;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of an extraction attempt.
 *
 * <p>Contains either a successfully extracted document or a failure state,
 * along with any warnings generated during extraction.
 */
public final class ExtractionResult {

    private final String resourceId;
    private final DetectedContentType detectedType;
    private final ExtractedDocument document;
    private final String errorMessage;
    private final List<String> warnings;

    private ExtractionResult(String resourceId, DetectedContentType detectedType,
                             ExtractedDocument document, String errorMessage, List<String> warnings) {
        this.resourceId = resourceId;
        this.detectedType = detectedType;
        this.document = document;
        this.errorMessage = errorMessage;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    /**
     * Creates a successful result.
     *
     * @param resourceId   must not be null
     * @param detectedType must not be null
     * @param document     must not be null
     */
    public static ExtractionResult success(String resourceId,
                                           DetectedContentType detectedType,
                                           ExtractedDocument document) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId must not be null");
        }
        if (detectedType == null) {
            throw new IllegalArgumentException("detectedType must not be null");
        }
        if (document == null) {
            throw new IllegalArgumentException("document must not be null for a successful result");
        }
        return new ExtractionResult(resourceId, detectedType, document, null,
                Collections.<String>emptyList());
    }

    /**
     * Creates a successful result with warnings.
     *
     * @param resourceId   must not be null
     * @param detectedType must not be null
     * @param document     must not be null
     * @param warnings     must not be null; defensively copied
     */
    public static ExtractionResult successWithWarnings(String resourceId,
                                                       DetectedContentType detectedType,
                                                       ExtractedDocument document,
                                                       List<String> warnings) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId must not be null");
        }
        if (detectedType == null) {
            throw new IllegalArgumentException("detectedType must not be null");
        }
        if (document == null) {
            throw new IllegalArgumentException("document must not be null for a successful result");
        }
        if (warnings == null) {
            throw new IllegalArgumentException("warnings must not be null; use an empty list instead");
        }
        return new ExtractionResult(resourceId, detectedType, document, null, warnings);
    }

    /**
     * Creates a failure result.
     *
     * @param resourceId   must not be null
     * @param detectedType may be null if detection itself failed
     * @param errorMessage must not be null or blank
     */
    public static ExtractionResult failure(String resourceId,
                                           DetectedContentType detectedType,
                                           String errorMessage) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId must not be null");
        }
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            throw new IllegalArgumentException("errorMessage must not be null or blank");
        }
        return new ExtractionResult(resourceId, detectedType, null, errorMessage,
                Collections.<String>emptyList());
    }

    /** Returns the stable id of the resource (source-specific, e.g. the file path). */
    public String resourceId() {
        return resourceId;
    }

    /** Returns the detected content type, or {@code null} if detection failed. */
    public DetectedContentType detectedType() {
        return detectedType;
    }

    /** Returns the extracted document, or {@code null} if extraction failed. */
    public ExtractedDocument document() {
        return document;
    }

    /** Returns {@code true} if extraction was successful. */
    public boolean isSuccess() {
        return document != null;
    }

    /** Returns the error message, or {@code null} if successful. */
    public String errorMessage() {
        return errorMessage;
    }

    /** Returns any warnings from extraction (may be empty). */
    public List<String> warnings() {
        return warnings;
    }
}
