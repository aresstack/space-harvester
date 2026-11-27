package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * One IMMUTABLE captured version of a source page: a changed website yields a NEW capture, the old one
 * stays. Only captures are citable — SERP snippets and other {@link SearchObservation}s are discovery
 * data and never become evidence. Structural blocks keep the document's own segmentation (headings,
 * paragraphs, lists …); the semantic pipeline refines WITHIN suitable blocks, never across them.
 */
public final class SourceCapture {

    /** The document's own structure — it takes precedence over semantic boundary detection. */
    public enum BlockKind { HEADING, PARAGRAPH, LIST_ITEM, TABLE_ROW, QUOTE, CODE }

    public static final class StructuralBlock {
        private final String blockId;
        private final BlockKind kind;
        private final String headingPath;
        private final String text;

        public StructuralBlock(String blockId, BlockKind kind, String headingPath, String text) {
            this.blockId = blockId == null ? "" : blockId;
            this.kind = kind == null ? BlockKind.PARAGRAPH : kind;
            this.headingPath = headingPath == null ? "" : headingPath;
            this.text = text == null ? "" : text;
        }

        public String getBlockId() {
            return blockId;
        }

        public BlockKind getKind() {
            return kind;
        }

        public String getHeadingPath() {
            return headingPath;
        }

        public String getText() {
            return text;
        }
    }

    private final String captureId;
    private final String sourceId;
    private final String canonicalUrl;
    private final long capturedAtMillis;
    private final String checksum;
    private final String title;
    private final String author;
    private final List<StructuralBlock> blocks;

    public SourceCapture(String captureId, String sourceId, String canonicalUrl, long capturedAtMillis,
                         String checksum, String title, String author, List<StructuralBlock> blocks) {
        this.captureId = captureId == null ? "" : captureId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.canonicalUrl = canonicalUrl == null ? "" : canonicalUrl;
        this.capturedAtMillis = capturedAtMillis;
        this.checksum = checksum == null ? "" : checksum;
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.blocks = blocks == null ? Collections.<StructuralBlock>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<StructuralBlock>(blocks));
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public long getCapturedAtMillis() {
        return capturedAtMillis;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public List<StructuralBlock> getBlocks() {
        return blocks;
    }
}
