package com.aresstack.spaceharvester.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One chapter of a page export (#115): the title of the page, its current content and its child pages in the order of
 * the source. The tree is complete when it reaches a writer; every output format writes from it and none loads
 * anything itself. Immutable.
 *
 * <p>Since #132 a chapter may carry export Markdown with the figures of the page placed as Markdown images
 * ({@code ![caption](<src>)}), and the figures themselves ({@link #images()}) with their bytes or the reason they are
 * missing.</p>
 *
 * <p>Space Harvester: without the {@code KnowledgeDocument} of the Enterprise AI Client; the content is the Markdown
 * itself. A chapter may name an {@link #anchor()}: the Word writer sets it as a bookmark on the chapter heading, and
 * Markdown links {@code #<anchor>} jump there.</p>
 */
public final class ExportDocumentNode {

    private final String title;
    private final String anchor;
    private final String markdown;
    private final List<ExportImage> images;
    private final List<ExportDocumentNode> children;

    /**
     * @param title    the chapter heading; blank becomes "Ohne Titel"
     * @param markdown the text of the chapter; {@code null} for a node without own content (a space root)
     * @param children the child chapters in source order
     */
    public ExportDocumentNode(String title, String markdown, List<ExportDocumentNode> children) {
        this(title, null, markdown, null, children);
    }

    /**
     * @param anchor   the jump target of the chapter, or {@code null}
     * @param images   the figures {@code markdown} refers to, in document order
     */
    public ExportDocumentNode(String title, String anchor, String markdown, List<ExportImage> images,
                              List<ExportDocumentNode> children) {
        String heading = title == null ? "" : title.trim();
        this.title = heading.isEmpty() ? "Ohne Titel" : heading;
        this.anchor = anchor == null || anchor.trim().isEmpty() ? null : anchor.trim();
        this.markdown = markdown;
        this.images = images == null ? Collections.<ExportImage>emptyList()
                : Collections.unmodifiableList(new ArrayList<ExportImage>(images));
        this.children = children == null ? Collections.<ExportDocumentNode>emptyList()
                : Collections.unmodifiableList(new ArrayList<ExportDocumentNode>(children));
    }

    public String title() {
        return title;
    }

    /** The jump target of the chapter; {@code null} if it has none. */
    public String anchor() {
        return anchor;
    }

    /** Return the text of the chapter in Markdown, with its figures placed; empty without content. */
    public String text() {
        return markdown == null ? "" : markdown;
    }

    /** Return the figures of this chapter (not of its children) in document order. */
    public List<ExportImage> images() {
        return images;
    }

    public List<ExportDocumentNode> children() {
        return children;
    }

    /** Return the number of chapters of this subtree, this one included. */
    public int size() {
        int size = 1;
        for (ExportDocumentNode child : children) {
            size += child.size();
        }
        return size;
    }
}
