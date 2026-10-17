package com.aresstack.enterpriseai.proasteion.emporion.deigma;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single block of extracted content within a document.
 *
 * <p>Blocks represent logical units such as paragraphs, headings,
 * tables, code sections or metadata entries.
 */
public final class ExtractedBlock {

    private final int index;
    private final BlockKind kind;
    private final String text;
    private final Map<String, String> attributes;

    public ExtractedBlock(int index, BlockKind kind, String text, Map<String, String> attributes) {
        if (index < 0) {
            throw new IllegalArgumentException("Block index must not be negative");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Block kind must not be null");
        }
        this.index = index;
        this.kind = kind;
        this.text = text;
        this.attributes = attributes == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }

    public ExtractedBlock(int index, BlockKind kind, String text) {
        this(index, kind, text, null);
    }

    /** Returns the zero-based position of this block within the document. */
    public int index() {
        return index;
    }

    /** Returns the structural kind of this block. */
    public BlockKind kind() {
        return kind;
    }

    /** Returns the text content of this block, or {@code null} if not applicable. */
    public String text() {
        return text;
    }

    /** Returns optional attributes as an unmodifiable map. */
    public Map<String, String> attributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "ExtractedBlock{" + index + ", " + kind + ", length=" + (text != null ? text.length() : 0) + "}";
    }
}
