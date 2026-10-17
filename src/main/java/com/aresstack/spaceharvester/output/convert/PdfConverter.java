package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionLimitExceededException;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Block;
import com.aresstack.spaceharvester.output.convert.StorageDocument.CodeBlock;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Heading;
import com.aresstack.spaceharvester.output.convert.StorageDocument.ImageRef;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Inline;
import com.aresstack.spaceharvester.output.convert.StorageDocument.LineBreak;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Link;
import com.aresstack.spaceharvester.output.convert.StorageDocument.ListBlock;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Note;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Panel;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Paragraph;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Rule;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Table;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Text;
import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

/**
 * PDF (#30): alle Seiten eines Exports in einer Datei {@code pages.pdf}, linear in Baumreihenfolge, mit Lesezeichen
 * entlang des Seitenbaums und Sprüngen zwischen Seiten desselben Exports. Der Inhalt kommt wie bei
 * {@link MarkdownConverter} aus dem Storage-Format, mit denselben Formatverlusten.
 *
 * <p>Schrift: ohne Konfiguration die Standardschriften Helvetica und Courier (WinAnsi, deckt westeuropäische Sprachen
 * ab); Zeichen außerhalb erscheinen als {@code ?}. Mit {@code space-harvester.export.pdf.font} wird eine
 * TrueType-Schrift eingebettet, die dann alle ihre Zeichen darstellt. Die Datei hat feste Kennungen und keine
 * Zeitstempel, damit gleicher Input dieselben Bytes ergibt.
 */
public final class PdfConverter implements ExportConverter {

    public static final String FORMAT = "pdf";
    public static final String VERSION = "pdf/1";

    private static final float MARGIN = 56;
    /** Mindestbreite für Text, auch bei tiefer Verschachtelung oder vielen Tabellenspalten. */
    private static final float MIN_TEXT_WIDTH = 40;
    /**
     * Höchste Seitenzahl einer PDF-Datei (#90): Seitenbaum, Gliederung und Verweise wachsen im Speicher, bevor
     * {@code max-converted-bytes} greift; darüber scheitert nur dieser Export ({@code CONVERSION_TOO_LARGE}).
     */
    static final int MAX_PAGES = 20_000;
    private static final float BODY = 10.5f;
    private static final float CODE = 8.5f;
    private static final float[] HEADINGS = {18, 15.5f, 13.5f, 12, 11, 10.5f};
    private static final Color TEXT = Color.BLACK;
    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final Color LINK = new Color(0x05, 0x63, 0xC1);
    private static final Color NOTE = new Color(0xC5, 0x5A, 0x11);
    private static final Color SHADE = new Color(0xF2, 0xF2, 0xF2);
    private static final Color BAR = new Color(0x44, 0x72, 0xC4);

    private final Path font;
    private final int maxPages;

    /** Mit den Standardschriften. */
    public PdfConverter() {
        this(null);
    }

    /** @param font TrueType-Schrift zum Einbetten oder {@code null} für die Standardschriften */
    public PdfConverter(Path font) {
        this(font, MAX_PAGES);
    }

    PdfConverter(Path font, int maxPages) {
        this.font = font;
        this.maxPages = maxPages;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String mediaType() {
        return "application/pdf";
    }

    @Override
    public String fileName() {
        return "pages.pdf";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) throws IOException {
        StorageParser parser = new StorageParser(PageLinks.of(input.headers(), input.spaceKey()));
        try (PDDocument document = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            Layout layout = new Layout(document, fonts(document), maxPages);
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Space " + input.spaceKey() + " (" + input.sourceId() + ")");
            info.setProducer("space-harvester " + VERSION);
            document.setDocumentInformation(info);
            MessageDigest identity = sha256();
            identity.update((input.sourceId() + "\n" + input.spaceKey() + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                input.forEachPage(page -> {
                    identity.update((page.pageId() + "\t" + page.snapshot().payload().rawSha256() + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    try {
                        layout.page(page, parser);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            layout.finish();
            // Feste Dateikennung statt einer aus der Uhrzeit: gleicher Input, gleiche Bytes.
            byte[] id = identity.digest();
            COSArray ids = new COSArray();
            ids.add(new COSString(id));
            ids.add(new COSString(id));
            document.getDocument().getTrailer().setItem(COSName.ID, ids);
            document.save(out);
        }
    }

    private Fonts fonts(PDDocument document) throws IOException {
        PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
        if (font != null) {
            PDFont embedded = PDType0Font.load(document, font.toFile());
            return new Fonts(embedded, embedded, embedded, embedded, mono, true);
        }
        return new Fonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE), mono, false);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    /** Die Schriften und welche Zeichen sie darstellen können. */
    private static final class Fonts {

        final PDFont regular;
        final PDFont bold;
        final PDFont italic;
        final PDFont boldItalic;
        final PDFont mono;
        private final boolean embedded;
        private final Map<PDFont, Map<Integer, Boolean>> supported = new HashMap<>();

        Fonts(PDFont regular, PDFont bold, PDFont italic, PDFont boldItalic, PDFont mono, boolean embedded) {
            this.regular = regular;
            this.bold = bold;
            this.italic = italic;
            this.boldItalic = boldItalic;
            this.mono = mono;
            this.embedded = embedded;
        }

        PDFont of(boolean isBold, boolean isItalic, boolean code) {
            if (code) {
                return mono;
            }
            return isBold ? (isItalic ? boldItalic : bold) : (isItalic ? italic : regular);
        }

        /** Nur darstellbare Zeichen; Tabulatoren werden Leerzeichen, nicht darstellbare {@code ?}. */
        String safe(PDFont font, String text) {
            StringBuilder safe = new StringBuilder(text.length());
            Map<Integer, Boolean> known = supported.computeIfAbsent(font, f -> new HashMap<>());
            text.codePoints().forEach(c -> {
                if (c == '\t') {
                    safe.append("    ");
                } else if (c < 0x20 || c == 0x7F) {
                    // Steuerzeichen fallen weg.
                } else if (known.computeIfAbsent(c, point -> encodable(font, point))) {
                    safe.appendCodePoint(c);
                } else {
                    safe.append('?');
                }
            });
            return safe.toString();
        }

        private boolean encodable(PDFont font, int codePoint) {
            if (embedded && font != mono && Character.isSupplementaryCodePoint(codePoint)) {
                return false;
            }
            try {
                font.encode(new String(Character.toChars(codePoint)));
                return true;
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        }
    }

    /** Ein gleich formatiertes Stück Text in einer Zeile. */
    private record Segment(String text, PDFont font, float size, Color color, String link, float width) {
    }

    /** Eine gesetzte Zeile. */
    private record Line(List<Segment> segments, float size) {

        float leading() {
            return size * 1.35f;
        }
    }

    /** Setzt Blöcke fortlaufend auf A4-Seiten. */
    private static final class Layout {

        private final PDDocument document;
        private final Fonts fonts;
        private final PDRectangle format = PDRectangle.A4;
        private final float width = PDRectangle.A4.getWidth() - 2 * MARGIN;
        private final PDDocumentOutline outline = new PDDocumentOutline();
        private final List<PDOutlineItem> outlineStack = new ArrayList<>();
        /** Ziel je Seite des Exports: PDF-Seite und Höhe ihrer Überschrift. */
        private final Map<String, PDPageXYZDestination> anchors = new HashMap<>();
        /** Interne Verweise, deren Ziel erst am Ende feststeht. */
        private final List<Map.Entry<PDAnnotationLink, String>> pendingLinks = new ArrayList<>();
        private PDPage page;
        private PDPageContentStream content;
        private float y;
        /** Linke Ränder offener Kästen, für deren senkrechten Strich. */
        private final List<Float> bars = new ArrayList<>();

        private final int maxPages;

        Layout(PDDocument document, Fonts fonts, int maxPages) {
            this.document = document;
            this.maxPages = maxPages;
            this.fonts = fonts;
            document.getDocumentCatalog().setDocumentOutline(outline);
        }

        void page(ConvertiblePage source, StorageParser parser) throws IOException {
            PageMetadata metadata = source.header().metadata();
            int level = Math.min(6, source.depth() + 1);
            String title = metadata.title() == null || metadata.title().isBlank() ? "Seite " + source.pageId()
                    : metadata.title();
            if (page == null || source.depth() == 0 && y < format.getHeight() - MARGIN) {
                newPage();
            }
            List<Line> heading = wrap(List.of(new Text(title, true, false, false)), HEADINGS[level - 1], TEXT,
                    0);
            ensure(heading.getFirst().leading() + 12);
            y -= 8;
            PDPageXYZDestination destination = new PDPageXYZDestination();
            destination.setPage(page);
            destination.setTop((int) y);
            anchors.put(source.pageId(), destination);
            outline(title, destination, source.depth());
            draw(heading, 0);
            StringBuilder facts = new StringBuilder("Seite " + source.pageId());
            if (metadata.version() != null) {
                facts.append(" · Version ").append(metadata.version());
            }
            List<Inline> factLine = new ArrayList<>(List.of(new Text(facts.toString(), false, true, false)));
            if (metadata.url() != null) {
                factLine.add(new Text(" · ", false, true, false));
                factLine.add(new Link("In Confluence öffnen", metadata.url().toString()));
            }
            draw(wrap(factLine, 8.5f, MUTED, 0), 0);
            y -= 6;
            List<Block> blocks = PageBody.storage(source.snapshot().payload()).map(parser::parse)
                    .orElse(List.of(new Note("Die Payload enthält keinen Inhalt im Storage-Format")));
            blocks(blocks, level, 0);
        }

        private void outline(String title, PDPageXYZDestination destination, int depth) {
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(title);
            item.setDestination(destination);
            while (outlineStack.size() > depth) {
                outlineStack.removeLast();
            }
            if (outlineStack.isEmpty()) {
                outline.addLast(item);
            } else {
                outlineStack.getLast().addLast(item);
            }
            outlineStack.add(item);
        }

        private void blocks(List<Block> blocks, int headingOffset, float indent) throws IOException {
            for (Block block : blocks) {
                block(block, headingOffset, indent);
            }
        }

        private void block(Block block, int headingOffset, float indent) throws IOException {
            switch (block) {
                case Heading heading -> {
                    int level = Math.min(6, headingOffset + heading.level());
                    List<Line> lines = wrap(bold(heading.content()), HEADINGS[level - 1], TEXT, indent);
                    ensure(lines.getFirst().leading() * Math.min(2, lines.size()) + 6);
                    y -= 6;
                    draw(lines, indent);
                    y -= 2;
                }
                case Paragraph paragraph -> {
                    draw(wrap(paragraph.content(), BODY, TEXT, indent), indent);
                    y -= 5;
                }
                case ListBlock list -> list(list, headingOffset, indent);
                case Table table -> table(table, indent);
                case CodeBlock code -> code(code, indent);
                case Panel panel -> {
                    float bar = MARGIN + indent + 2;
                    bars.add(bar);
                    draw(wrap(List.of(new Text(panel.label(), true, false, false)), BODY, TEXT, indent + 10),
                            indent + 10);
                    blocks(panel.content(), headingOffset, indent + 10);
                    bars.removeLast();
                    y -= 4;
                }
                case Rule rule -> {
                    ensure(10);
                    y -= 5;
                    content.setStrokingColor(MUTED);
                    content.setLineWidth(0.5f);
                    content.moveTo(MARGIN + indent, y);
                    content.lineTo(MARGIN + width, y);
                    content.stroke();
                    y -= 5;
                }
                case Note note -> {
                    draw(wrap(List.of(new Text("[Export: " + note.text() + "]", false, true, false)), BODY, NOTE,
                            indent), indent);
                    y -= 5;
                }
            }
        }

        private void list(ListBlock list, int headingOffset, float indent) throws IOException {
            for (int i = 0; i < list.items().size(); i++) {
                String marker = list.ordered() ? (i + 1) + "." : "•";
                if (list.checked() != null) {
                    marker = list.checked().get(i) ? "[x]" : "[ ]";
                }
                float itemIndent = indent + 18;
                // Erst die Marke, dann der Inhalt: Leseprogramme folgen der Zeichenreihenfolge.
                ensure(BODY * 1.35f);
                text(fonts.regular, BODY, TEXT, MARGIN + indent + 4, y - BODY, fonts.safe(fonts.regular, marker));
                List<Block> item = list.items().get(i);
                if (item.isEmpty()) {
                    y -= BODY * 1.35f;
                }
                for (Block block : item) {
                    block(block, headingOffset, itemIndent);
                }
            }
            y -= 2;
        }

        private void code(CodeBlock code, float indent) throws IOException {
            float lineHeight = CODE * 1.3f;
            float charWidth = fonts.mono.getStringWidth("M") / 1000 * CODE;
            int perLine = Math.max(10, (int) ((width - indent - 8) / charWidth));
            for (String line : code.code().stripTrailing().split("\n", -1)) {
                String safe = fonts.safe(fonts.mono, line);
                do {
                    String chunk = safe.length() > perLine ? safe.substring(0, perLine) : safe;
                    safe = safe.substring(chunk.length());
                    ensure(lineHeight);
                    content.setNonStrokingColor(SHADE);
                    content.addRect(MARGIN + indent, y - lineHeight, width - indent, lineHeight);
                    content.fill();
                    text(fonts.mono, CODE, TEXT, MARGIN + indent + 4, y - CODE, chunk);
                    drawBars(lineHeight);
                    y -= lineHeight;
                } while (!safe.isEmpty());
            }
            y -= 6;
        }

        private void table(Table table, float indent) throws IOException {
            int columns = table.rows().stream().mapToInt(List::size).max().orElse(0);
            if (columns == 0) {
                return;
            }
            float columnWidth = Math.max((width - indent) / columns, MIN_TEXT_WIDTH);
            float padding = 3;
            for (int r = 0; r < table.rows().size(); r++) {
                boolean header = r == 0 && table.headerRow();
                List<List<Line>> cells = new ArrayList<>();
                float height = 0;
                for (int c = 0; c < columns; c++) {
                    List<Block> cell = c < table.rows().get(r).size() ? table.rows().get(r).get(c) : List.of();
                    List<Line> lines = cellLines(cell, header, columnWidth - 2 * padding);
                    cells.add(lines);
                    height = Math.max(height, (float) lines.stream().mapToDouble(Line::leading).sum());
                }
                height += 2 * padding;
                if (height > format.getHeight() - 2 * MARGIN) {
                    // Eine Zeile höher als eine Seite: Zellen nacheinander statt nebeneinander.
                    for (List<Line> lines : cells) {
                        draw(lines, indent);
                    }
                    continue;
                }
                ensure(height);
                for (int c = 0; c < columns; c++) {
                    float x = MARGIN + indent + c * columnWidth;
                    if (header) {
                        content.setNonStrokingColor(SHADE);
                        content.addRect(x, y - height, columnWidth, height);
                        content.fill();
                    }
                    content.setStrokingColor(MUTED);
                    content.setLineWidth(0.5f);
                    content.addRect(x, y - height, columnWidth, height);
                    content.stroke();
                    float lineY = y - padding;
                    for (Line line : cells.get(c)) {
                        drawLine(line, x + padding, lineY);
                        lineY -= line.leading();
                    }
                }
                drawBars(height);
                y -= height;
            }
            y -= 8;
        }

        /** Inhalt einer Zelle als Zeilen; Blöcke außer Absätzen werden zu Text. */
        private List<Line> cellLines(List<Block> blocks, boolean header, float cellWidth) {
            List<Line> lines = new ArrayList<>();
            for (Block block : blocks) {
                List<Inline> inlines = switch (block) {
                    case Paragraph paragraph -> paragraph.content();
                    case Heading heading -> bold(heading.content());
                    case CodeBlock code -> List.of(new Text(code.code(), false, false, true));
                    case ListBlock list -> list.items().stream().map(item -> (Inline) Text.plain("• "
                            + StorageDocument.text(item))).toList();
                    case Panel panel -> List.of(Text.plain(panel.label() + ": "
                            + StorageDocument.text(panel.content())));
                    case Note note -> List.of(new Text("[Export: " + note.text() + "]", false, true, false));
                    case Table nested -> List.of(new Text("[Export: verschachtelte Tabelle nicht übernommen]",
                            false, true, false));
                    case Rule rule -> List.of();
                };
                lines.addAll(wrapTo(header ? bold(inlines) : inlines, BODY - 1, TEXT, cellWidth));
            }
            if (lines.isEmpty()) {
                lines.add(new Line(List.of(), BODY - 1));
            }
            return lines;
        }

        private static List<Inline> bold(List<Inline> content) {
            return content.stream().map(inline -> inline instanceof Text text
                    ? new Text(text.text(), true, text.italic(), text.code()) : inline).toList();
        }

        private List<Line> wrap(List<Inline> content, float size, Color color, float indent) {
            return wrapTo(content, size, color, width - indent);
        }

        /** Bricht Inline-Inhalt an Leerzeichen in Zeilen der gegebenen Breite um; zu lange Wörter zeichenweise. */
        private List<Line> wrapTo(List<Inline> content, float size, Color color, float maxWidth) {
            // Tiefe Verschachtelung oder sehr viele Spalten dürfen die Breite nicht auf null drücken.
            maxWidth = Math.max(maxWidth, MIN_TEXT_WIDTH);
            List<Line> lines = new ArrayList<>();
            List<Segment> current = new ArrayList<>();
            float used = 0;
            for (Inline inline : content) {
                if (inline instanceof LineBreak) {
                    lines.add(new Line(current, size));
                    current = new ArrayList<>();
                    used = 0;
                    continue;
                }
                PDFont font;
                Color segmentColor = color;
                String link = null;
                String text;
                switch (inline) {
                    case Text plain -> {
                        font = fonts.of(plain.bold(), plain.italic(), plain.code());
                        text = plain.text();
                    }
                    case Link target -> {
                        font = fonts.regular;
                        text = target.text().isEmpty() && target.target() != null ? target.target() : target.text();
                        if (target.target() != null && (target.target().startsWith("#page-")
                                || target.target().matches("(?i)(https?|mailto):.*"))) {
                            link = target.target();
                            segmentColor = LINK;
                        }
                    }
                    case ImageRef image -> {
                        font = fonts.italic;
                        text = "[Bild: " + image.alt()
                                + (image.reference().isEmpty() ? "" : " – " + image.reference()) + "]";
                    }
                    default -> throw new IllegalStateException();
                }
                for (String token : fonts.safe(font, text).split("(?<= )")) {
                    float tokenWidth = width(font, size, token);
                    if (used + tokenWidth > maxWidth && !current.isEmpty()) {
                        lines.add(new Line(current, size));
                        current = new ArrayList<>();
                        used = 0;
                        if (token.isBlank()) {
                            continue;
                        }
                    }
                    if (tokenWidth > maxWidth) {
                        // Ein überlanges Wort Zeichen für Zeichen aufteilen, jedes Zeichen nur einmal messen: sonst
                        // kostet ein langes Wort ohne Leerzeichen quadratisch Rechenzeit (Durchsicht, docs/SECURITY.md).
                        int start = 0;
                        while (start < token.length()) {
                            int end = start;
                            float partWidth = 0;
                            while (end < token.length()) {
                                int next = end + Character.charCount(token.codePointAt(end));
                                float charWidth = width(font, size, token.substring(end, next));
                                if (end > start && partWidth + charWidth > maxWidth) {
                                    break;
                                }
                                partWidth += charWidth;
                                end = next;
                            }
                            String part = token.substring(start, end);
                            start = end;
                            if (start < token.length()) {
                                current.add(new Segment(part, font, size, segmentColor, link, partWidth));
                                lines.add(new Line(current, size));
                                current = new ArrayList<>();
                                used = 0;
                            } else {
                                token = part;
                                tokenWidth = partWidth;
                            }
                        }
                    }
                    if (!token.isEmpty()) {
                        current.add(new Segment(token, font, size, segmentColor, link, tokenWidth));
                        used += tokenWidth;
                    }
                }
            }
            if (!current.isEmpty() || lines.isEmpty()) {
                lines.add(new Line(current, size));
            }
            return lines;
        }

        private static float width(PDFont font, float size, String text) {
            try {
                return font.getStringWidth(text) / 1000 * size;
            } catch (IOException | IllegalArgumentException e) {
                return text.length() * size * 0.6f;
            }
        }

        private void draw(List<Line> lines, float indent) throws IOException {
            for (Line line : lines) {
                ensure(line.leading());
                drawLine(line, MARGIN + indent, y);
                drawBars(line.leading());
                y -= line.leading();
            }
        }

        /** Zeichnet eine Zeile; {@code top} ist die Oberkante. */
        private void drawLine(Line line, float x, float top) throws IOException {
            float baseline = top - line.size();
            for (Segment segment : line.segments()) {
                text(segment.font(), segment.size(), segment.color(), x, baseline, segment.text());
                if (segment.link() != null && !segment.text().isBlank()) {
                    link(segment.link(), x, baseline - 2, segment.width(), segment.size() + 3);
                }
                x += segment.width();
            }
        }

        private void text(PDFont font, float size, Color color, float x, float baseline, String text)
                throws IOException {
            if (text.isEmpty()) {
                return;
            }
            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, baseline);
            content.showText(text);
            content.endText();
        }

        private void link(String target, float x, float bottom, float linkWidth, float height) throws IOException {
            PDAnnotationLink annotation = new PDAnnotationLink();
            annotation.setRectangle(new PDRectangle(x, bottom, linkWidth, height));
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(0);
            annotation.setBorderStyle(border);
            if (target.startsWith("#page-")) {
                pendingLinks.add(Map.entry(annotation, target));
            } else {
                PDActionURI action = new PDActionURI();
                action.setURI(target);
                annotation.setAction(action);
            }
            List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
            annotations.add(annotation);
            page.setAnnotations(annotations);
        }

        private void drawBars(float height) throws IOException {
            for (float bar : bars) {
                content.setNonStrokingColor(BAR);
                content.addRect(bar, y - height, 2, height);
                content.fill();
            }
        }

        private void ensure(float height) throws IOException {
            if (page == null || y - height < MARGIN) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            if (document.getNumberOfPages() >= maxPages) {
                throw ConversionLimitExceededException.pages("pages.pdf", maxPages);
            }
            if (content != null) {
                content.close();
            }
            page = new PDPage(format);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = format.getHeight() - MARGIN;
        }

        /** Löst interne Verweise auf und schließt die letzte Seite. */
        void finish() throws IOException {
            if (page == null) {
                newPage();
            }
            content.close();
            Map<String, String> anchorsByTarget = new HashMap<>();
            for (String pageId : anchors.keySet()) {
                anchorsByTarget.put("#" + PageLinks.anchor(pageId), pageId);
            }
            for (Map.Entry<PDAnnotationLink, String> link : pendingLinks) {
                String pageId = anchorsByTarget.get(link.getValue());
                if (pageId != null) {
                    PDActionGoTo action = new PDActionGoTo();
                    action.setDestination(anchors.get(pageId));
                    link.getKey().setAction(action);
                }
            }
        }
    }
}
