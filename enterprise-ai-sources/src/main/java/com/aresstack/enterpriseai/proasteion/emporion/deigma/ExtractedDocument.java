package com.aresstack.enterpriseai.proasteion.emporion.deigma;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of extracting content from a resource.
 *
 * <p>Contains ordered blocks of extracted content and optional metadata
 * such as title and detected content type.
 */
public final class ExtractedDocument {

    private final String title;
    private final DetectedContentType contentType;
    private final List<ExtractedBlock> blocks;

    private ExtractedDocument(Builder builder) {
        this.title = builder.title;
        this.contentType = builder.contentType;
        this.blocks = Collections.unmodifiableList(new ArrayList<ExtractedBlock>(builder.blocks));
    }

    /** Returns the document title, or {@code null} if unknown. */
    public String title() {
        return title;
    }

    /** Returns the detected content type. */
    public DetectedContentType contentType() {
        return contentType;
    }

    /** Returns the ordered list of extracted blocks. */
    public List<ExtractedBlock> blocks() {
        return blocks;
    }

    /**
     * Returns the combined plain text of all blocks, joined by newlines.
     * Useful as a convenience for indexing or simple display.
     */
    public String combinedText() {
        StringBuilder sb = new StringBuilder();
        for (ExtractedBlock block : blocks) {
            if (block.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(block.text());
            }
        }
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String title;
        private DetectedContentType contentType;
        private final List<ExtractedBlock> blocks = new ArrayList<ExtractedBlock>();

        private Builder() {}

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder contentType(DetectedContentType contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder addBlock(ExtractedBlock block) {
            if (block == null) {
                throw new IllegalArgumentException("Block must not be null");
            }
            this.blocks.add(block);
            return this;
        }

        public ExtractedDocument build() {
            return new ExtractedDocument(this);
        }
    }
}
