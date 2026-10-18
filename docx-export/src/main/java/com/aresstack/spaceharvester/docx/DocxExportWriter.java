package com.aresstack.spaceharvester.docx;

import com.aresstack.enterpriseai.astu.acropolis.knowledge.ExportDocumentNode;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes the export tree as a Word document (#115): every page a chapter whose heading style follows its depth in the
 * tree (root Heading 1, child Heading 2 …), so Word's navigation pane shows the tree; the content of a page goes below
 * with its own headings shifted under the chapter. With more than one page a table of contents field comes first;
 * Word fills in its page numbers when it updates the fields on opening.
 */
public final class DocxExportWriter implements ExportWriter {

    /** The table of contents over all heading levels, with hyperlinks, without page numbers in web view. */
    static final String TOC_INSTRUCTION = "TOC \\o \"1-" + MarkdownDocxRenderer.MAX_HEADING_LEVEL + "\" \\h \\z \\u";
    static final String TOC_TITLE = "Inhalt";
    static final String TOC_PLACEHOLDER = "Das Inhaltsverzeichnis erscheint, sobald Word die Felder aktualisiert (F9).";

    private final ExportGraphics graphics;

    public DocxExportWriter() {
        this(ExportGraphics.standard());
    }

    /** @param graphics the figures of this export (#132); it also collects the ones that are missing */
    public DocxExportWriter(ExportGraphics graphics) {
        if (graphics == null) {
            throw new IllegalArgumentException("graphics must not be null");
        }
        this.graphics = graphics;
    }

    @Override
    public void write(ExportDocumentNode root, OutputStream out) throws IOException {
        graphics.with(root);
        XWPFDocument document = new XWPFDocument();
        try {
            MarkdownDocxRenderer renderer = new MarkdownDocxRenderer(document, graphics);
            document.getProperties().getCoreProperties().setTitle(root.title());
            document.getProperties().getCoreProperties().setCreator("Enterprise AI Client");
            if (root.size() > 1) {
                tableOfContents(document, renderer);
            }
            chapter(renderer, root, 1);
            document.write(out);
        } finally {
            document.close();
        }
    }

    private static void tableOfContents(XWPFDocument document, MarkdownDocxRenderer renderer) {
        XWPFParagraph title = renderer.paragraph();
        title.setStyle(MarkdownDocxRenderer.STYLE_TITLE);
        title.createRun().setText(TOC_TITLE);
        XWPFParagraph field = renderer.paragraph();
        CTSimpleField toc = field.getCTP().addNewFldSimple();
        toc.setInstr(TOC_INSTRUCTION);
        toc.addNewR().addNewT().setStringValue(TOC_PLACEHOLDER);
        renderer.paragraph().createRun().addBreak(BreakType.PAGE);
        document.enforceUpdateFields();
    }

    private static void chapter(MarkdownDocxRenderer renderer, ExportDocumentNode node, int depth) {
        XWPFParagraph heading = renderer.paragraph();
        XWPFRun run = heading.createRun();
        run.setText(MarkdownDocxRenderer.clean(node.title()));
        if (depth <= MarkdownDocxRenderer.MAX_HEADING_LEVEL) {
            heading.setStyle(MarkdownDocxRenderer.STYLE_HEADING + depth);
        } else {
            run.setBold(true);
        }
        renderer.markdown(node.text(), false, depth);
        for (ExportDocumentNode child : node.children()) {
            chapter(renderer, child, depth + 1);
        }
    }
}
