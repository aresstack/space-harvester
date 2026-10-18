package com.aresstack.spaceharvester.document;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.HtmlInlineComment;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import java.awt.Color;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the export tree as a PDF (#115). Every page of the tree is a chapter that starts on a new PDF page; the
 * document outline (the bookmarks of a PDF viewer) has one entry per chapter, nested exactly like the tree, each
 * jumping to the start of its chapter. With more than one chapter a table of contents comes first, built from the same
 * chapter list: indented by depth, with page numbers, every line a link to its chapter. Content is the canonical
 * Markdown of the page laid out as headings, paragraphs, lists, quotes, code and tables in the standard PDF fonts;
 * characters those fonts cannot show are replaced.
 *
 * <p>Figures (#132): a Markdown image the {@link ExportGraphics} of the export resolves is drawn as a picture where it
 * stands, scaled to the text width and to one page, with its caption below; a Mermaid fence is drawn as the diagram.
 * A figure that does not resolve leaves a visible notice and is reported by the graphics.</p>
 *
 * <p>Space Harvester: PDFBox 3 instead of 2. Chapters are handed in one by one in tree order
 * ({@link #open(String, int, Set)}); the content streams go to a temporary file (PDFBox scratch file), so a long export
 * keeps only the page tree, the outline and the links in memory, and {@code maxPages} ends a document that would grow
 * beyond it ({@link PageLimitExceededException}). Instead of the standard fonts a TrueType font can be embedded, which
 * then shows all its characters. A chapter with an anchor is the target of Markdown links {@code #<anchor>}. An image
 * inside a table cell is a notice (resolved or missing) instead of "[Bild: …]". Inline {@code <br>} breaks the line, an
 * empty header row is left out (as in {@link MarkdownDocxRenderer}). The file is deterministic: a fixed document ID
 * from the caller and no dates.</p>
 */
public final class PdfExportWriter {

    static final String TOC_TITLE = "Inhalt";

    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float MARGIN = 56f;
    private static final float FOOTER_OFFSET = 28f;
    private static final float BODY_SIZE = 10.5f;
    private static final float SMALL_SIZE = 9f;
    private static final float CODE_SIZE = 8.5f;
    private static final float FOOTER_SIZE = 8f;
    private static final float LEADING = 1.35f;
    private static final float[] CHAPTER_SIZES = {20f, 17f, 15f, 13.5f, 12.5f, 12f};
    private static final float[] HEADING_SIZES = {16f, 14f, 12.5f, 11.5f, 11f, 10.5f};
    private static final float TOC_TITLE_SIZE = 18f;
    private static final float TOC_SIZE = 10.5f;
    private static final float TOC_LEADING = 18f;
    private static final float TOC_INDENT = 14f;
    private static final float TOC_TITLE_SPACE = 44f;
    private static final float LIST_INDENT = 16f;
    private static final float QUOTE_INDENT = 14f;
    private static final float CELL_PADDING = 4f;
    private static final Color TEXT = new Color(0x1F, 0x2A, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x6B, 0x6B);
    private static final Color LINK = new Color(0x1F, 0x5F, 0xAF);
    private static final Color RULE = new Color(0xBF, 0xC7, 0xD0);
    private static final Color CODE_FILL = new Color(0xF4, 0xF5, 0xF7);
    private static final Color HEADER_FILL = new Color(0xEA, 0xF0, 0xF6);
    private static final String BULLET = "•";
    private static final String REPLACEMENT = "?";

    private static final float PIXELS_TO_POINTS = 0.75f;
    private static final String MISSING = "Abbildung nicht verfügbar";
    private static final java.util.regex.Pattern BREAK = java.util.regex.Pattern.compile("(?i)<br\\s*/?>");

    /** Pages of one PDF at most, unless the caller says otherwise. */
    public static final int MAX_PAGES = 20_000;

    private final Parser parser;
    private final ExportGraphics graphics;
    private final Path font;
    private final int maxPages;
    private final String creator;

    public PdfExportWriter() {
        this(ExportGraphics.standard());
    }

    /** @param graphics the figures of this export; it also collects the ones that are missing */
    public PdfExportWriter(ExportGraphics graphics) {
        this(graphics, null, MAX_PAGES, "Space Harvester");
    }

    /**
     * @param font     a TrueType font to embed, or {@code null} for the standard fonts (WinAnsi; other characters
     *                 become {@code ?})
     * @param maxPages more PDF pages end the document with {@link PageLimitExceededException}
     */
    public PdfExportWriter(ExportGraphics graphics, Path font, int maxPages, String creator) {
        if (graphics == null) {
            throw new IllegalArgumentException("graphics must not be null");
        }
        this.graphics = graphics;
        this.font = font;
        this.maxPages = maxPages;
        this.creator = creator == null ? "" : creator;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create(), StrikethroughExtension.create(),
                AutolinkExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    public void write(ExportDocumentNode root, OutputStream out) throws IOException {
        Set<String> anchors = new HashSet<String>();
        collectAnchors(root, anchors);
        try (Chapters chapters = open(root.title(), root.size(), anchors)) {
            chapter(chapters, root, 1);
            chapters.finish(out, null);
        }
    }

    private static void collectAnchors(ExportDocumentNode node, Set<String> anchors) {
        if (node.anchor() != null) {
            anchors.add(node.anchor());
        }
        for (ExportDocumentNode child : node.children()) {
            collectAnchors(child, anchors);
        }
    }

    private static void chapter(Chapters chapters, ExportDocumentNode node, int depth) throws IOException {
        chapters.chapter(node.title(), node.anchor(), node.text(), node.images(), depth);
        for (ExportDocumentNode child : node.children()) {
            chapter(chapters, child, depth + 1);
        }
    }

    /**
     * Start a PDF whose chapters follow in tree order.
     *
     * @param chapters how many chapters will come (the table of contents is laid out before them)
     * @param anchors  the anchors of all chapters; links to others stay text
     */
    public Chapters open(String title, int chapters, Set<String> anchors) throws IOException {
        return new Chapters(title, chapters, anchors);
    }

    /** The PDF would get more pages than allowed; nothing is cut, the document fails. */
    public static final class PageLimitExceededException extends IOException {
        private final int maxPages;

        PageLimitExceededException(int maxPages) {
            super("PDF überschreitet " + maxPages + " Seiten");
            this.maxPages = maxPages;
        }

        public int maxPages() {
            return maxPages;
        }
    }

    /** One PDF being written; {@link #close()} releases it, with or without {@link #finish(OutputStream, byte[])}. */
    public final class Chapters implements Closeable {
        private final PDDocument document;
        private final Layout layout;
        private final String title;

        private Chapters(String title, int chapters, Set<String> anchors) throws IOException {
            this.title = title == null ? "" : title;
            document = new PDDocument(IOUtils.createTempFileOnlyStreamCache());
            try {
                layout = new Layout(document, fonts(document), anchors == null ? Collections.<String>emptySet()
                        : anchors);
                layout.begin(chapters);
            } catch (IOException | RuntimeException e) {
                document.close();
                throw e;
            }
        }

        /** Write the next chapter in tree order; {@code depth} 1 is the root. */
        public void chapter(String title, String anchor, String markdown, List<ExportImage> images, int depth)
                throws IOException {
            graphics.with(images);
            try {
                layout.chapter(title, anchor, markdown == null ? "" : markdown, depth);
            } finally {
                graphics.forget();
                layout.pictures.clear();
            }
        }

        /**
         * Lay out the table of contents, outline, links and page numbers and write the PDF to {@code out}.
         *
         * @param documentId the fixed file identifier (for example a hash of the input), or {@code null}
         */
        public void finish(OutputStream out, byte[] documentId) throws IOException {
            layout.finish();
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle(title);
            info.setCreator(creator);
            if (documentId != null) {
                COSArray ids = new COSArray();
                ids.add(new COSString(documentId));
                ids.add(new COSString(documentId));
                document.getDocument().getTrailer().setItem(COSName.ID, ids);
            }
            document.save(out);
        }

        @Override
        public void close() throws IOException {
            document.close();
        }
    }

    private Fonts fonts(PDDocument document) throws IOException {
        PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
        if (font != null) {
            PDFont embedded = PDType0Font.load(document, font.toFile());
            return new Fonts(embedded, embedded, embedded, embedded, mono);
        }
        return new Fonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE), mono);
    }

    /** The fonts of one document: four cuts for text (one embedded font for all) and a monospaced one. */
    private static final class Fonts {
        final PDFont regular;
        final PDFont bold;
        final PDFont italic;
        final PDFont boldItalic;
        final PDFont mono;

        Fonts(PDFont regular, PDFont bold, PDFont italic, PDFont boldItalic, PDFont mono) {
            this.regular = regular;
            this.bold = bold;
            this.italic = italic;
            this.boldItalic = boldItalic;
            this.mono = mono;
        }
    }

    // ------------------------------------------------------------------ chapters

    /** One chapter with its place in the tree and where it starts; its content is already on the pages. */
    private static final class Chapter {
        final String title;
        final int depth;
        final Chapter parent;
        PDPage page;
        float top;
        PDOutlineItem outline;

        Chapter(String title, int depth, Chapter parent) {
            this.title = title;
            this.depth = depth;
            this.parent = parent;
        }
    }

    /** A link to a chapter whose start may not be laid out yet; resolved in {@code finish}. */
    private static final class PendingLink {
        final PDAnnotationLink link;
        final String anchor;

        PendingLink(PDAnnotationLink link, String anchor) {
            this.link = link;
            this.anchor = anchor;
        }
    }

    /** A piece of a line in one font. */
    private static final class Run {
        final String text;
        final PDFont font;
        final float size;
        final Color color;
        final String url;
        /** Jump target inside the document instead of {@code url}. */
        final String anchor;

        Run(String text, PDFont font, float size, Color color, String url) {
            this(text, font, size, color, url, null);
        }

        Run(String text, PDFont font, float size, Color color, String url, String anchor) {
            this.text = text;
            this.font = font;
            this.size = size;
            this.color = color;
            this.url = url;
            this.anchor = anchor;
        }

        Run with(String other) {
            return new Run(other, font, size, color, url, anchor);
        }
    }

    /** How inline text is styled at the current place. */
    private static final class Style {
        boolean bold;
        boolean italic;
        boolean code;
        float size = BODY_SIZE;
        Color color = TEXT;
        String url;
        String anchor;

        Style copy() {
            Style copy = new Style();
            copy.bold = bold;
            copy.italic = italic;
            copy.code = code;
            copy.size = size;
            copy.color = color;
            copy.url = url;
            copy.anchor = anchor;
            return copy;
        }

        PDFont font(Fonts fonts) {
            if (code) {
                return fonts.mono;
            }
            if (bold) {
                return italic ? fonts.boldItalic : fonts.bold;
            }
            return italic ? fonts.italic : fonts.regular;
        }
    }

    /** Where a block is written: left edge, width, quote depth. */
    private static final class Frame {
        final float x;
        final float width;
        final int quotes;
        final int headingShift;

        Frame(float x, float width, int quotes, int headingShift) {
            this.x = x;
            this.width = width;
            this.quotes = quotes;
            this.headingShift = headingShift;
        }

        Frame indent(float by, boolean quote) {
            return new Frame(x + by, Math.max(40f, width - by), quotes + (quote ? 1 : 0), headingShift);
        }
    }

    // ------------------------------------------------------------------ layout

    private final class Layout {
        private final PDDocument document;
        private final Fonts fonts;
        private final Set<String> anchors;
        private final Map<PDFont, Map<Integer, Boolean>> encodable = new HashMap<PDFont, Map<Integer, Boolean>>();
        private final Map<ExportGraphics.Graphic, PDImageXObject> pictures =
                new HashMap<ExportGraphics.Graphic, PDImageXObject>();
        private final List<Chapter> chapters = new ArrayList<Chapter>();
        private final Map<String, Chapter> anchored = new HashMap<String, Chapter>();
        private final List<PendingLink> pendingLinks = new ArrayList<PendingLink>();
        private final List<PDPage> tocPages = new ArrayList<PDPage>();
        private PDPage page;
        private PDPageContentStream stream;
        private float y;

        Layout(PDDocument document, Fonts fonts, Set<String> anchors) {
            this.document = document;
            this.fonts = fonts;
            this.anchors = anchors;
        }

        void begin(int chapterCount) throws IOException {
            if (chapterCount > 1) {
                for (int i = 0; i < tocPageCount(chapterCount); i++) {
                    PDPage toc = new PDPage(PAGE_SIZE);
                    addPage(toc);
                    tocPages.add(toc);
                }
            }
        }

        void chapter(String title, String anchor, String markdown, int depth) throws IOException {
            Chapter parent = null;
            for (int i = chapters.size() - 1; i >= 0; i--) {
                if (chapters.get(i).depth < depth) {
                    parent = chapters.get(i);
                    break;
                }
            }
            Chapter chapter = new Chapter(title, depth, parent);
            chapters.add(chapter);
            if (anchor != null) {
                anchored.put(anchor, chapter);
            }
            chapter(chapter, markdown);
        }

        void finish() throws IOException {
            closeStream();
            if (!tocPages.isEmpty()) {
                tableOfContents(tocPages, chapters);
            }
            outline(chapters);
            for (PendingLink pending : pendingLinks) {
                Chapter target = anchored.get(pending.anchor);
                if (target != null) {
                    PDActionGoTo action = new PDActionGoTo();
                    action.setDestination(destination(target));
                    pending.link.setAction(action);
                }
            }
            pageNumbers();
        }

        // -------------------------------------------------------------- pages

        private float contentTop() {
            return PAGE_SIZE.getHeight() - MARGIN;
        }

        private float contentWidth() {
            return PAGE_SIZE.getWidth() - 2 * MARGIN;
        }

        private void addPage(PDPage added) throws IOException {
            if (document.getNumberOfPages() >= maxPages) {
                throw new PageLimitExceededException(maxPages);
            }
            document.addPage(added);
        }

        private void newPage() throws IOException {
            closeStream();
            page = new PDPage(PAGE_SIZE);
            addPage(page);
            stream = new PDPageContentStream(document, page);
            y = contentTop();
        }

        private void closeStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        /** Go to a new page unless {@code height} still fits above the bottom margin. */
        private void ensure(float height) throws IOException {
            if (y - height < MARGIN) {
                newPage();
            }
        }

        // -------------------------------------------------------------- chapter

        private void chapter(Chapter chapter, String text) throws IOException {
            newPage();
            chapter.page = page;
            chapter.top = y;
            float size = CHAPTER_SIZES[Math.min(chapter.depth, CHAPTER_SIZES.length) - 1];
            Style style = new Style();
            style.bold = true;
            style.size = size;
            Frame frame = new Frame(MARGIN, contentWidth(), 0, chapter.depth);
            paragraph(Arrays.asList(new Run(chapter.title, style.font(fonts), size, TEXT, null)), frame, null);
            y -= size * 0.25f;
            stroke(RULE, 0.8f, MARGIN, y, MARGIN + contentWidth(), y);
            y -= BODY_SIZE;
            if (!text.trim().isEmpty()) {
                blocks(parser.parse(text.replace("\r\n", "\n")), frame);
            }
        }

        // -------------------------------------------------------------- blocks

        private void blocks(Node container, Frame frame) throws IOException {
            for (Node child = container.getFirstChild(); child != null; child = child.getNext()) {
                block(child, frame);
            }
        }

        private void block(Node node, Frame frame) throws IOException {
            if (node instanceof HtmlCommentBlock) {
                return;
            }
            if (node instanceof Heading) {
                int level = Math.max(1, ((Heading) node).getLevel()) + frame.headingShift;
                float size = HEADING_SIZES[Math.min(level - 1, HEADING_SIZES.length) - 1];
                Style style = new Style();
                style.bold = true;
                style.size = size;
                y -= size * 0.5f;
                paragraph(inlines(node, style), frame, null);
                y -= size * 0.2f;
                return;
            }
            if (node instanceof Paragraph) {
                flow(node, base(frame), frame, null);
                y -= BODY_SIZE * 0.5f;
                return;
            }
            if (node instanceof FencedCodeBlock) {
                FencedCodeBlock fenced = (FencedCodeBlock) node;
                String code = fenced.getContentChars().toString();
                if (isMermaid(fenced.getInfo().toString())) {
                    ExportGraphics.Graphic diagram = graphics.mermaid(code);
                    if (diagram != null) {
                        figure(diagram, "", frame);
                        return;
                    }
                }
                code(code, frame);
                return;
            }
            if (node instanceof IndentedCodeBlock) {
                code(((IndentedCodeBlock) node).getContentChars().toString(), frame);
                return;
            }
            if (node instanceof BulletList || node instanceof OrderedList) {
                list(node, frame);
                return;
            }
            if (node instanceof BlockQuote) {
                blocks(node, frame.indent(QUOTE_INDENT, true));
                return;
            }
            if (node instanceof TableBlock) {
                table((TableBlock) node, frame);
                return;
            }
            if (node instanceof ThematicBreak) {
                ensure(BODY_SIZE);
                y -= BODY_SIZE * 0.5f;
                stroke(RULE, 0.8f, frame.x, y, frame.x + frame.width, y);
                y -= BODY_SIZE * 0.5f;
                return;
            }
            if (node instanceof HtmlBlock) {
                Style style = base(frame);
                paragraph(Arrays.asList(new Run(node.getChars().toString().trim(), style.font(fonts), style.size,
                        style.color, null)), frame, null);
                return;
            }
            if (node.hasChildren()) {
                blocks(node, frame);
                return;
            }
            String rest = node.getChars().toString().trim();
            if (!rest.isEmpty()) {
                Style style = base(frame);
                paragraph(Arrays.asList(new Run(rest, style.font(fonts), style.size, style.color, null)), frame,
                        null);
            }
        }

        private Style base(Frame frame) {
            Style style = new Style();
            if (frame.quotes > 0) {
                style.italic = true;
                style.color = MUTED;
            }
            return style;
        }

        private void list(Node list, Frame frame) throws IOException {
            int number = list instanceof OrderedList ? ((OrderedList) list).getStartNumber() : 0;
            Frame inner = frame.indent(LIST_INDENT, false);
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                if (!(item instanceof ListItem)) {
                    block(item, frame);
                    continue;
                }
                String marker = list instanceof OrderedList ? (number++) + "." : BULLET;
                boolean first = true;
                for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                    if (first && child instanceof Paragraph) {
                        flow(child, base(frame), inner, marker);
                        y -= BODY_SIZE * 0.2f;
                    } else {
                        if (first) {
                            paragraph(Arrays.asList(new Run("", fonts.regular, BODY_SIZE, TEXT, null)), inner,
                                    marker);
                        }
                        block(child, inner);
                    }
                    first = false;
                }
            }
            y -= BODY_SIZE * 0.3f;
        }

        private void code(String code, Frame frame) throws IOException {
            String body = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
            float lineHeight = CODE_SIZE * LEADING;
            PDFont font = fonts.mono;
            float inner = frame.width - 2 * CELL_PADDING;
            y -= CODE_SIZE * 0.3f;
            for (String raw : body.split("\n", -1)) {
                for (String line : breakChars(fit(raw.replace("\t", "    "), font), font, CODE_SIZE, inner, inner)) {
                    ensure(lineHeight);
                    fill(CODE_FILL, frame.x, y - lineHeight, frame.width, lineHeight);
                    text(line, font, CODE_SIZE, TEXT, frame.x + CELL_PADDING, y - CODE_SIZE);
                    y -= lineHeight;
                }
            }
            y -= BODY_SIZE * 0.6f;
        }

        private void table(TableBlock block, Frame frame) throws IOException {
            List<List<Node>> rows = new ArrayList<List<Node>>();
            List<Boolean> header = new ArrayList<Boolean>();
            int columns = 0;
            for (Node section = block.getFirstChild(); section != null; section = section.getNext()) {
                if (section instanceof TableSeparator) {
                    continue;
                }
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    if (!(row instanceof TableRow)) {
                        continue;
                    }
                    if (section instanceof TableHead && plain(row).isEmpty()) {
                        continue; // Space Harvester: leere Kopfzeile einer Tabelle ohne Kopf (Confluence) entfällt
                    }
                    List<Node> cells = new ArrayList<Node>();
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell) {
                            cells.add(cell);
                        }
                    }
                    rows.add(cells);
                    header.add(section instanceof TableHead);
                    columns = Math.max(columns, cells.size());
                }
            }
            if (rows.isEmpty() || columns == 0) {
                return;
            }
            float columnWidth = frame.width / columns;
            float lineHeight = SMALL_SIZE * LEADING;
            float maxRow = contentTop() - MARGIN;
            for (int r = 0; r < rows.size(); r++) {
                List<List<List<Run>>> cellLines = new ArrayList<List<List<Run>>>();
                int height = 1;
                for (Node cell : rows.get(r)) {
                    Style style = new Style();
                    style.size = SMALL_SIZE;
                    style.bold = header.get(r);
                    List<List<Run>> lines = wrap(inlines(cell, style), columnWidth - 2 * CELL_PADDING);
                    cellLines.add(lines);
                    height = Math.max(height, lines.size());
                }
                // Continue a row taller than a page on the next page instead of cutting its lines.
                int linesPerPage = Math.max(1, (int) ((maxRow - 2 * CELL_PADDING) / lineHeight));
                for (int start = 0; start < height; start += linesPerPage) {
                    int count = Math.min(linesPerPage, height - start);
                    float rowHeight = count * lineHeight + 2 * CELL_PADDING;
                    ensure(rowHeight);
                    float top = y;
                    if (header.get(r)) {
                        fill(HEADER_FILL, frame.x, top - rowHeight, frame.width, rowHeight);
                    }
                    for (int c = 0; c < columns; c++) {
                        float cellX = frame.x + c * columnWidth;
                        if (c < cellLines.size()) {
                            List<List<Run>> lines = cellLines.get(c);
                            float lineY = top - CELL_PADDING;
                            for (int l = start; l < Math.min(lines.size(), start + count); l++) {
                                drawLine(lines.get(l), cellX + CELL_PADDING, lineY - SMALL_SIZE);
                                lineY -= lineHeight;
                            }
                        }
                        strokeRect(RULE, cellX, top - rowHeight, columnWidth, rowHeight);
                    }
                    y = top - rowHeight;
                }
            }
            y -= BODY_SIZE * 0.6f;
        }

        // -------------------------------------------------------------- figures

        /**
         * Write the inline content of {@code parent} as paragraphs, every image that resolves drawn as a picture
         * between them where it stands.
         */
        private void flow(Node parent, Style style, Frame frame, String marker) throws IOException {
            List<Run> runs = new ArrayList<Run>();
            String pending = marker;
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                Image image = image(node);
                if (image == null) {
                    inline(node, style, runs);
                    continue;
                }
                String caption = plain(image);
                ExportGraphics.Graphic graphic = graphics.image(image.getUrl().unescape(), caption);
                if (graphic == null) {
                    Style missing = style.copy();
                    missing.italic = true;
                    missing.color = MUTED;
                    runs.add(run("[" + MISSING + (caption.isEmpty() ? "" : ": " + caption) + "]", missing));
                    continue;
                }
                if (!isBlank(runs) || pending != null) {
                    paragraph(runs, frame, pending);
                    pending = null;
                }
                runs = new ArrayList<Run>();
                figure(graphic, caption, frame);
            }
            if (!isBlank(runs) || pending != null) {
                paragraph(runs, frame, pending);
            }
        }

        /** The image of an inline node: an image, or a link around nothing but an image. */
        private Image image(Node node) {
            if (node instanceof Image) {
                return (Image) node;
            }
            if (node instanceof Link && node.getFirstChild() instanceof Image
                    && node.getFirstChild() == node.getLastChild()) {
                return (Image) node.getFirstChild();
            }
            return null;
        }

        private boolean isBlank(List<Run> runs) {
            for (Run run : runs) {
                if (!run.text.trim().isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /** Draw a picture at most as wide as the frame and as high as a page, its caption below. */
        private void figure(ExportGraphics.Graphic graphic, String caption, Frame frame) throws IOException {
            float width = graphic.width() * PIXELS_TO_POINTS;
            float height = graphic.height() * PIXELS_TO_POINTS;
            float maxHeight = contentTop() - MARGIN - 3 * SMALL_SIZE * LEADING;
            float scale = Math.min(1f, Math.min(frame.width / width, maxHeight / height));
            width *= scale;
            height *= scale;
            y -= BODY_SIZE * 0.3f;
            ensure(height);
            PDImageXObject picture = pictures.get(graphic);
            if (picture == null) {
                picture = LosslessFactory.createFromImage(document, graphic.image());
                pictures.put(graphic, picture);
            }
            stream.drawImage(picture, frame.x, y - height, width, height);
            y -= height + BODY_SIZE * 0.4f;
            if (!caption.trim().isEmpty()) {
                Style style = new Style();
                style.italic = true;
                style.size = SMALL_SIZE;
                style.color = MUTED;
                paragraph(Arrays.asList(run(caption.trim(), style)), frame, null);
            }
            y -= BODY_SIZE * 0.5f;
        }

        // -------------------------------------------------------------- inline

        private List<Run> inlines(Node parent, Style style) {
            List<Run> runs = new ArrayList<Run>();
            collectInlines(parent, style, runs);
            return runs;
        }

        private void collectInlines(Node parent, Style style, List<Run> runs) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                inline(node, style, runs);
            }
        }

        private void inline(Node node, Style style, List<Run> runs) {
            if (node instanceof Text) {
                runs.add(run(node.getChars().unescape(), style));
            } else if (node instanceof StrongEmphasis) {
                Style inner = style.copy();
                inner.bold = true;
                collectInlines(node, inner, runs);
            } else if (node instanceof Emphasis) {
                Style inner = style.copy();
                inner.italic = true;
                collectInlines(node, inner, runs);
            } else if (node instanceof Code) {
                Style inner = style.copy();
                inner.code = true;
                runs.add(run(((Code) node).getText().toString(), inner));
            } else if (node instanceof SoftLineBreak) {
                runs.add(run(" ", style));
            } else if (node instanceof HardLineBreak
                    || node instanceof HtmlInline && BREAK.matcher(node.getChars()).matches()) {
                runs.add(run("\n", style));
            } else if (node instanceof Link) {
                link(runs, plain(node), ((Link) node).getUrl().unescape(), style);
            } else if (node instanceof AutoLink) {
                String url = ((AutoLink) node).getUrl().unescape();
                link(runs, url, url, style);
            } else if (node instanceof MailLink) {
                String mail = ((MailLink) node).getText().toString();
                link(runs, mail, "mailto:" + mail, style);
            } else if (node instanceof Image) {
                // inside a table cell: no room for the picture; whether it resolves is still told and reported
                String alt = plain(node);
                Style notice = style.copy();
                notice.italic = true;
                notice.color = MUTED;
                ExportGraphics.Graphic graphic = graphics.image(((Image) node).getUrl().unescape(), alt);
                runs.add(run("[" + (graphic == null ? MISSING : "Abbildung in der Tabelle nicht dargestellt")
                        + (alt.isEmpty() ? "" : ": " + alt) + "]", notice));
            } else if (node instanceof HtmlInlineComment) {
                return;
            } else if (node.hasChildren()) {
                collectInlines(node, style, runs);
            } else {
                runs.add(run(node.getChars().unescape(), style));
            }
        }

        private void link(List<Run> runs, String label, String url, Style style) {
            Style inner = style.copy();
            if (url.startsWith("#") && anchors.contains(url.substring(1))) {
                inner.color = LINK;
                inner.anchor = url.substring(1);
            } else if (MarkdownDocxRenderer.isWebLink(url)) {
                inner.color = LINK;
                inner.url = url.trim();
            }
            runs.add(run(label.isEmpty() ? url : label, inner));
        }

        private Run run(String text, Style style) {
            return new Run(text, style.font(fonts), style.size, style.color, style.url, style.anchor);
        }

        // -------------------------------------------------------------- lines

        /** Write runs as wrapped lines; {@code marker} (a bullet or number) hangs left of the first one. */
        private void paragraph(List<Run> runs, Frame frame, String marker) throws IOException {
            List<List<Run>> lines = wrap(runs, frame.width);
            float size = runs.isEmpty() ? BODY_SIZE : runs.get(0).size;
            float lineHeight = size * LEADING;
            if (lines.isEmpty()) {
                lines.add(new ArrayList<Run>());
            }
            for (int i = 0; i < lines.size(); i++) {
                ensure(lineHeight);
                float baseline = y - size;
                if (i == 0 && marker != null) {
                    String shown = fit(marker, fonts.regular);
                    float width = width(shown, fonts.regular, BODY_SIZE);
                    text(shown, fonts.regular, BODY_SIZE, TEXT, frame.x - width - 5f, baseline);
                }
                if (frame.quotes > 0) {
                    stroke(RULE, 2f, frame.x - QUOTE_INDENT / 2f, y, frame.x - QUOTE_INDENT / 2f, y - lineHeight);
                }
                drawLine(lines.get(i), frame.x, baseline);
                y -= lineHeight;
            }
        }

        /** Break runs into lines no wider than {@code width}; a word longer than a line is cut. */
        private List<List<Run>> wrap(List<Run> runs, float width) throws IOException {
            List<List<Run>> lines = new ArrayList<List<Run>>();
            List<Run> line = new ArrayList<Run>();
            float used = 0f;
            for (Run run : runs) {
                String text = fit(run.text, run.font);
                int start = 0;
                while (start < text.length()) {
                    char c = text.charAt(start);
                    if (c == '\n') {
                        lines.add(line);
                        line = new ArrayList<Run>();
                        used = 0f;
                        start++;
                        continue;
                    }
                    int end = start;
                    if (c == ' ') {
                        while (end < text.length() && text.charAt(end) == ' ') {
                            end++;
                        }
                    } else {
                        while (end < text.length() && text.charAt(end) != ' ' && text.charAt(end) != '\n') {
                            end++;
                        }
                    }
                    String token = text.substring(start, end);
                    start = end;
                    boolean space = token.charAt(0) == ' ';
                    if (space && line.isEmpty()) {
                        continue; // no space at the start of a line
                    }
                    float tokenWidth = width(token, run.font, run.size);
                    if (used + tokenWidth <= width) {
                        line.add(run.with(token));
                        used += tokenWidth;
                        continue;
                    }
                    if (space) {
                        lines.add(line);
                        line = new ArrayList<Run>();
                        used = 0f;
                        continue;
                    }
                    if (!line.isEmpty() && tokenWidth <= width) {
                        lines.add(line);
                        line = new ArrayList<Run>();
                        used = 0f;
                        line.add(run.with(token));
                        used = tokenWidth;
                        continue;
                    }
                    for (String piece : breakChars(token, run.font, run.size, width - used, width)) {
                        float pieceWidth = width(piece, run.font, run.size);
                        if (used + pieceWidth > width && !line.isEmpty()) {
                            lines.add(line);
                            line = new ArrayList<Run>();
                            used = 0f;
                        }
                        line.add(run.with(piece));
                        used += pieceWidth;
                    }
                }
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
            return lines;
        }

        /**
         * Cut {@code text} into pieces; the first fits {@code firstWidth}, the others {@code width} (Space Harvester:
         * the width of the frame, a table cell or an indented block, instead of the whole page width).
         */
        private List<String> breakChars(String text, PDFont font, float size, float firstWidth, float width)
                throws IOException {
            List<String> pieces = new ArrayList<String>();
            float limit = Math.max(size, firstWidth);
            StringBuilder piece = new StringBuilder();
            float used = 0f;
            // Space Harvester: widths add up per character; measuring the growing piece took quadratic time
            for (int i = 0; i < text.length(); ) {
                int end = i + Character.charCount(text.codePointAt(i));
                float charWidth = width(text.substring(i, end), font, size);
                if (used + charWidth > limit && piece.length() > 0) {
                    pieces.add(piece.toString());
                    piece.setLength(0);
                    used = 0f;
                    limit = Math.max(size, width);
                }
                piece.append(text, i, end);
                used += charWidth;
                i = end;
            }
            pieces.add(piece.toString());
            return pieces;
        }

        private void drawLine(List<Run> line, float x, float baseline) throws IOException {
            float at = x;
            for (Run run : line) {
                float width = width(run.text, run.font, run.size);
                if (!run.text.trim().isEmpty()) {
                    text(run.text, run.font, run.size, run.color, at, baseline);
                }
                if (run.url != null || run.anchor != null) {
                    PDAnnotationLink link = new PDAnnotationLink();
                    link.setRectangle(new PDRectangle(at, baseline - run.size * 0.25f, width, run.size * 1.2f));
                    link.setBorderStyle(noBorder());
                    if (run.url != null) {
                        PDActionURI action = new PDActionURI();
                        action.setURI(run.url);
                        link.setAction(action);
                    } else {
                        pendingLinks.add(new PendingLink(link, run.anchor));
                    }
                    page.getAnnotations().add(link);
                }
                at += width;
            }
        }

        // -------------------------------------------------------------- table of contents and outline

        private int tocPageCount(int entries) {
            int first = Math.max(1, (int) ((contentTop() - MARGIN - TOC_TITLE_SPACE) / TOC_LEADING));
            int next = Math.max(1, (int) ((contentTop() - MARGIN) / TOC_LEADING));
            return entries <= first ? 1 : 1 + (entries - first + next - 1) / next;
        }

        private void tableOfContents(List<PDPage> pages, List<Chapter> chapters) throws IOException {
            int pageIndex = 0;
            page = pages.get(0);
            stream = new PDPageContentStream(document, page);
            y = contentTop();
            text(TOC_TITLE, fonts.bold, TOC_TITLE_SIZE, TEXT, MARGIN, y - TOC_TITLE_SIZE);
            y -= TOC_TITLE_SPACE;
            for (Chapter chapter : chapters) {
                if (y - TOC_LEADING < MARGIN) {
                    closeStream();
                    page = pages.get(++pageIndex);
                    stream = new PDPageContentStream(document, page);
                    y = contentTop();
                }
                PDFont font = chapter.depth == 1 ? fonts.bold : fonts.regular;
                String number = String.valueOf(document.getPages().indexOf(chapter.page) + 1);
                float numberWidth = width(number, font, TOC_SIZE);
                float indent = Math.min(contentWidth() / 2f, (chapter.depth - 1) * TOC_INDENT);
                float titleWidth = contentWidth() - indent - numberWidth - TOC_INDENT;
                String title = shorten(fit(chapter.title, font), font, TOC_SIZE, titleWidth);
                float baseline = y - TOC_SIZE;
                text(title, font, TOC_SIZE, TEXT, MARGIN + indent, baseline);
                text(number, font, TOC_SIZE, TEXT, MARGIN + contentWidth() - numberWidth, baseline);
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(MARGIN + indent, baseline - TOC_SIZE * 0.3f,
                        contentWidth() - indent, TOC_LEADING * 0.9f));
                link.setBorderStyle(noBorder());
                link.setDestination(destination(chapter));
                page.getAnnotations().add(link);
                y -= TOC_LEADING;
            }
            closeStream();
        }

        private void outline(List<Chapter> chapters) {
            PDDocumentOutline outline = new PDDocumentOutline();
            for (Chapter chapter : chapters) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(chapter.title);
                item.setDestination(destination(chapter));
                chapter.outline = item;
                PDOutlineNode parent = chapter.parent == null ? outline : chapter.parent.outline;
                parent.addLast(item);
            }
            if (!chapters.isEmpty()) {
                chapters.get(0).outline.openNode();
            }
            outline.openNode();
            document.getDocumentCatalog().setDocumentOutline(outline);
            document.getDocumentCatalog().setPageMode(PageMode.USE_OUTLINES);
        }

        private PDPageXYZDestination destination(Chapter chapter) {
            PDPageXYZDestination destination = new PDPageXYZDestination();
            destination.setPage(chapter.page);
            destination.setLeft(0);
            destination.setTop(Math.round(chapter.top + MARGIN / 2f));
            return destination;
        }

        private void pageNumbers() throws IOException {
            int total = document.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage numbered = document.getPage(i);
                PDPageContentStream footer = new PDPageContentStream(document, numbered,
                        PDPageContentStream.AppendMode.APPEND, true, true);
                PDFont footerFont = fonts.regular;
                try {
                    String label = (i + 1) + " / " + total;
                    float width = width(label, footerFont, FOOTER_SIZE);
                    footer.setNonStrokingColor(MUTED);
                    footer.beginText();
                    footer.setFont(footerFont, FOOTER_SIZE);
                    footer.newLineAtOffset((PAGE_SIZE.getWidth() - width) / 2f, FOOTER_OFFSET);
                    footer.showText(label);
                    footer.endText();
                } finally {
                    footer.close();
                }
            }
        }

        // -------------------------------------------------------------- drawing

        private void text(String text, PDFont font, float size, Color color, float x, float baseline)
                throws IOException {
            if (text.isEmpty()) {
                return;
            }
            stream.setNonStrokingColor(color);
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, baseline);
            stream.showText(text);
            stream.endText();
        }

        private void fill(Color color, float x, float bottom, float width, float height) throws IOException {
            stream.setNonStrokingColor(color);
            stream.addRect(x, bottom, width, height);
            stream.fill();
        }

        private void stroke(Color color, float lineWidth, float x1, float y1, float x2, float y2) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(lineWidth);
            stream.moveTo(x1, y1);
            stream.lineTo(x2, y2);
            stream.stroke();
        }

        private void strokeRect(Color color, float x, float bottom, float width, float height) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(0.6f);
            stream.addRect(x, bottom, width, height);
            stream.stroke();
        }

        // -------------------------------------------------------------- text helpers

        private float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        private String shorten(String text, PDFont font, float size, float width) throws IOException {
            if (width(text, font, size) <= width) {
                return text;
            }
            String ellipsis = "...";
            String shortened = text;
            while (shortened.length() > 1 && width(shortened + ellipsis, font, size) > width) {
                shortened = shortened.substring(0, shortened.length() - 1);
            }
            return shortened.trim() + ellipsis;
        }

        /** Keep what {@code font} can show; replace the rest (tabs as spaces, arrows as ASCII, else "?"). */
        private String fit(String text, PDFont font) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    out.append('\n');
                    continue;
                }
                if (codePoint == '\t' || codePoint == ' ' || Character.isWhitespace(codePoint)) {
                    out.append(' ');
                    continue;
                }
                if (Character.isISOControl(codePoint)) {
                    continue;
                }
                String replacement = canShow(codePoint, font) ? null : substitute(codePoint);
                if (replacement != null) {
                    out.append(replacement);
                } else if (canShow(codePoint, font)) {
                    out.appendCodePoint(codePoint);
                } else {
                    out.append(REPLACEMENT);
                }
            }
            return out.toString();
        }

        private boolean canShow(int codePoint, PDFont font) {
            Map<Integer, Boolean> known = encodable.get(font);
            if (known == null) {
                known = new HashMap<Integer, Boolean>();
                encodable.put(font, known);
            }
            Boolean shown = known.get(codePoint);
            if (shown == null) {
                try {
                    font.encode(new String(Character.toChars(codePoint)));
                    shown = font.getStringWidth(new String(Character.toChars(codePoint))) >= 0;
                } catch (IllegalArgumentException | IOException e) {
                    shown = Boolean.FALSE;
                }
                known.put(codePoint, shown);
            }
            return shown;
        }
    }

    private static String substitute(int codePoint) {
        switch (codePoint) {
            case 0x2192:
                return "->";
            case 0x2190:
                return "<-";
            case 0x2194:
                return "<->";
            case 0x21D2:
                return "=>";
            case 0x2264:
                return "<=";
            case 0x2265:
                return ">=";
            case 0x2260:
                return "!=";
            case 0x2713:
            case 0x2714:
                return "x";
            case 0x25E6:
            case 0x25AA:
                return "-";
            default:
                return null;
        }
    }

    static boolean isMermaid(String info) {
        String language = info == null ? "" : info.trim().split("\\s+")[0];
        return "mermaid".equalsIgnoreCase(language);
    }

    private static PDBorderStyleDictionary noBorder() {
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        return border;
    }

    private static String plain(Node node) {
        StringBuilder out = new StringBuilder();
        collectText(node, out);
        return out.toString().trim();
    }

    private static void collectText(Node node, StringBuilder out) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text) {
                out.append(child.getChars().unescape());
            } else if (child instanceof Code) {
                out.append(((Code) child).getText());
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                out.append(' ');
            } else if (child.hasChildren()) {
                collectText(child, out);
            } else {
                out.append(child.getChars().unescape());
            }
        }
    }
}
