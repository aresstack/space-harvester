package com.aresstack.enterpriseai.proasteion.emporion.deigma;

/**
 * Represents a detected content type for a resource.
 *
 * <p>Combines a MIME type string with a category classification that
 * helps downstream modules decide how to process the resource. For example,
 * source-code files are marked with {@link ContentCategory#SOURCE_CODE}
 * so that they can be handled separately (here: indexed as plain text).
 */
public final class DetectedContentType {

    private final String mimeType;
    private final ContentCategory category;
    private final String filenameHint;

    public DetectedContentType(String mimeType, ContentCategory category, String filenameHint) {
        if (mimeType == null || mimeType.isEmpty()) {
            throw new IllegalArgumentException("MIME type must not be null or empty");
        }
        if (category == null) {
            throw new IllegalArgumentException("Category must not be null");
        }
        this.mimeType = mimeType;
        this.category = category;
        this.filenameHint = filenameHint;
    }

    /** Returns the MIME type string (e.g. "text/plain", "application/pdf"). */
    public String mimeType() {
        return mimeType;
    }

    /** Returns the broad category classification. */
    public ContentCategory category() {
        return category;
    }

    /** Returns the filename hint used during detection, or {@code null}. */
    public String filenameHint() {
        return filenameHint;
    }

    @Override
    public String toString() {
        return "DetectedContentType{" + mimeType + ", " + category + "}";
    }
}
