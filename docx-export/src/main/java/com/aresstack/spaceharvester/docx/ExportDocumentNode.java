package com.aresstack.spaceharvester.docx;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One chapter of a page export (#115): the title of the page, its current content and its child pages in the order of
 * the source. The tree is complete when it leaves {@link KnowledgeExportUseCase}; every output format writes from it
 * and none loads anything itself. Immutable.
 *
 * <p>Since #132 a chapter may carry export Markdown that differs from the indexable text of the document: the same
 * text with the figures of the page placed as Markdown images ({@code ![caption](<src>)}), and the figures themselves
 * ({@link #images()}) with their bytes or the reason they are missing. The document and its index semantics stay
 * untouched.</p>
 */
public final class ExportDocumentNode {

    private final String title;
    private final KnowledgeDocument document;
    private final String markdown;
    private final List<ExportImage> images;
    private final List<ExportDocumentNode> children;

    /**
     * @param title    the chapter heading; blank falls back to the title of the document
     * @param document the content of the page; {@code null} for a node without own content (a space root)
     * @param children the child chapters in source order
     */
    public ExportDocumentNode(String title, KnowledgeDocument document, List<ExportDocumentNode> children) {
        this(title, document, null, null, children);
    }

    /**
     * @param markdown the export Markdown; {@code null} means the text of {@code document}
     * @param images   the figures {@code markdown} refers to, in document order
     */
    public ExportDocumentNode(String title, KnowledgeDocument document, String markdown, List<ExportImage> images,
                              List<ExportDocumentNode> children) {
        String heading = title == null ? "" : title.trim();
        if (heading.isEmpty() && document != null) {
            heading = document.resource().title().trim();
        }
        this.title = heading.isEmpty() ? (document == null ? "Ohne Titel" : document.id().value()) : heading;
        this.document = document;
        this.markdown = markdown;
        this.images = images == null ? Collections.<ExportImage>emptyList()
                : Collections.unmodifiableList(new ArrayList<ExportImage>(images));
        this.children = children == null ? Collections.<ExportDocumentNode>emptyList()
                : Collections.unmodifiableList(new ArrayList<ExportDocumentNode>(children));
    }

    public String title() {
        return title;
    }

    /** Return the page content; {@code null} for a node without own content. */
    public KnowledgeDocument document() {
        return document;
    }

    /** Return the text of the chapter in Markdown, with its figures placed; empty without content. */
    public String text() {
        if (markdown != null) {
            return markdown;
        }
        return document == null ? "" : document.text();
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
