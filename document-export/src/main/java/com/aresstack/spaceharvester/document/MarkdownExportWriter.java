package com.aresstack.spaceharvester.document;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes the export tree as one Markdown file (#115): every page a heading as deep as its place in the tree (root
 * {@code #}, child {@code ##} …), its content below with every own heading shifted under that one. A heading deeper
 * than Markdown's six levels becomes a bold line instead of an invalid heading.
 *
 * <p>Figures (#132): the export stays one file. Every figure the {@link ExportGraphics} of the export resolves is
 * embedded as a PNG {@code data:} URI, so the file reads offline and never points at a private source address; a
 * Mermaid fence keeps its editable code and gets the drawn diagram as an image below it. A figure of the page that
 * could not be loaded becomes a visible notice and is reported.</p>
 *
 * <p>Space Harvester: chapters can also be rendered one at a time ({@link #chapter(String, String, String, List,
 * int)}), so a long export never holds the whole file; a chapter with an anchor gets {@code <a id="…"></a>} before its
 * heading as the target of Markdown links {@code #<anchor>}. The title is written as given (the caller escapes it).
 * Every image that is not a web address counts as a figure of the export: unknown, it becomes the notice "Abbildung
 * nicht verfügbar" and is reported, like in Word and PDF (upstream it stayed as written).</p>
 */
public final class MarkdownExportWriter {

    /** The deepest heading Markdown knows. */
    static final int MAX_LEVEL = 6;

    /**
     * Space Harvester: where a figure goes. The default embeds it as a PNG {@code data:} URI (upstream behaviour); a
     * package export stores it next to the Markdown file and links it relatively.
     */
    public interface FigureLinks {

        FigureLinks EMBEDDED = (graphic, caption) -> ExportGraphics.dataUri(graphic);

        /** @return the link target of the drawn figure */
        String link(ExportGraphics.Graphic graphic, String caption);
    }

    private final Parser parser;
    private final ExportGraphics graphics;
    private final FigureLinks links;

    public MarkdownExportWriter() {
        this(ExportGraphics.standard());
    }

    /** @param graphics the figures of this export; it also collects the ones that are missing */
    public MarkdownExportWriter(ExportGraphics graphics) {
        this(graphics, FigureLinks.EMBEDDED);
    }

    /** @param links where the figures go, see {@link FigureLinks} */
    public MarkdownExportWriter(ExportGraphics graphics, FigureLinks links) {
        if (graphics == null) {
            throw new IllegalArgumentException("graphics must not be null");
        }
        if (links == null) {
            throw new IllegalArgumentException("links must not be null");
        }
        this.graphics = graphics;
        this.links = links;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Collections.singletonList(TablesExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    /**
     * Render one chapter: anchor, heading at {@code depth} (1 is the root) and its content with the figures of
     * {@code images} embedded, ending with a blank line. Unlike {@link #render(ExportDocumentNode)}, blank lines inside
     * the content (for example in code blocks) stay as they are.
     */
    public String chapter(String title, String anchor, String markdown, List<ExportImage> images, int depth) {
        graphics.with(images);
        try {
            StringBuilder out = new StringBuilder();
            if (anchor != null && !anchor.trim().isEmpty()) {
                out.append("<a id=\"").append(anchor.trim()).append("\"></a>\n\n");
            }
            out.append(heading(depth, title == null || title.trim().isEmpty() ? "Ohne Titel" : title)).append("\n\n");
            String content = markdown == null ? "" : markdown.replace("\r\n", "\n").trim();
            if (!content.isEmpty()) {
                out.append(embedFigures(shiftHeadings(content, depth))).append("\n\n");
            }
            return out.toString();
        } finally {
            graphics.forget();
        }
    }

    public void write(ExportDocumentNode root, OutputStream out) throws IOException {
        out.write(render(root).getBytes(StandardCharsets.UTF_8));
    }

    /** Return the whole tree as Markdown. */
    public String render(ExportDocumentNode root) {
        graphics.with(root);
        StringBuilder text = new StringBuilder();
        chapter(root, 1, text);
        return text.toString().replaceAll("\\n{3,}", "\n\n").trim() + "\n";
    }

    private void chapter(ExportDocumentNode node, int depth, StringBuilder out) {
        out.append(heading(depth, node.title())).append("\n\n");
        String content = node.text().replace("\r\n", "\n").trim();
        if (!content.isEmpty()) {
            out.append(embedFigures(shiftHeadings(content, depth))).append("\n\n");
        }
        for (ExportDocumentNode child : node.children()) {
            chapter(child, depth + 1, out);
        }
    }

    static String heading(int level, String text) {
        String line = text.replace('\n', ' ').trim();
        if (level > MAX_LEVEL) {
            return "**" + line + "**";
        }
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < level; i++) {
            marks.append('#');
        }
        return marks + " " + line;
    }

    /** Move every heading of {@code markdown} {@code shift} levels deeper; code blocks and other text stay as they are. */
    String shiftHeadings(String markdown, int shift) {
        if (shift <= 0) {
            return markdown;
        }
        Document document = parser.parse(markdown);
        List<Heading> headings = new ArrayList<Heading>();
        collect(document, headings);
        StringBuilder out = new StringBuilder(markdown);
        for (int i = headings.size() - 1; i >= 0; i--) {
            Heading heading = headings.get(i);
            if (heading.isSetextHeading() && !(heading.getParent() instanceof Document)) {
                continue; // a two-line heading inside a quote or list keeps its markers
            }
            int start = heading.getStartOffset();
            int end = heading.getEndOffset();
            while (end > start && (markdown.charAt(end - 1) == '\n' || markdown.charAt(end - 1) == '\r')) {
                end--;
            }
            out.replace(start, end, heading(heading.getLevel() + shift, heading.getText().toString()));
        }
        return out.toString();
    }

    /** Replace the figures of {@code markdown} by embedded pictures; Mermaid fences get the diagram below. */
    String embedFigures(String markdown) {
        Document document = parser.parse(markdown);
        List<Node> figures = new ArrayList<Node>();
        collectFigures(document, figures);
        StringBuilder out = new StringBuilder(markdown);
        for (int i = figures.size() - 1; i >= 0; i--) {
            Node node = figures.get(i);
            if (node instanceof FencedCodeBlock) {
                ExportGraphics.Graphic diagram = graphics.mermaid(((FencedCodeBlock) node).getContentChars().toString());
                if (diagram != null) {
                    int end = Math.min(out.length(), node.getEndOffset());
                    out.insert(end, (end > 0 && out.charAt(end - 1) == '\n' ? "" : "\n") + "\n![Mermaid-Diagramm]("
                            + links.link(diagram, "Mermaid-Diagramm") + ")\n");
                }
                continue;
            }
            Image image = (Image) node;
            String src = image.getUrl().unescape();
            if (!graphics.knows(src) && MarkdownDocxRenderer.isWebLink(src)) {
                continue; // an address of the text itself (e.g. a web image): stays as written
            }
            String caption = image.getText().toString().replaceAll("[\\[\\]\\r\\n]+", " ").trim();
            ExportGraphics.Graphic graphic = graphics.image(src, caption);
            String replacement = graphic == null
                    ? "*[Abbildung nicht verfügbar" + (caption.isEmpty() ? "" : ": " + caption) + "]*"
                    : "![" + caption + "](" + links.link(graphic, caption) + ")";
            out.replace(image.getStartOffset(), image.getEndOffset(), replacement);
        }
        return out.toString();
    }

    private static void collectFigures(Node node, List<Node> figures) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Image) {
                figures.add(child);
            } else if (child instanceof FencedCodeBlock) {
                if (PdfExportWriter.isMermaid(((FencedCodeBlock) child).getInfo().toString())) {
                    figures.add(child);
                }
            } else {
                collectFigures(child, figures);
            }
        }
    }

    private static void collect(Node node, List<Heading> headings) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading) {
                headings.add((Heading) child);
            } else {
                collect(child, headings);
            }
        }
    }
}
