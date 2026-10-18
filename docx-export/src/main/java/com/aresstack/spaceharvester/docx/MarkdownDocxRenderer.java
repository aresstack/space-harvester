package com.aresstack.spaceharvester.docx;

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
import com.vladsch.flexmark.ast.HtmlInlineComment;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.RefNode;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
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
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Writes Markdown structurally into one Word document: headings as real heading styles (Word's navigation pane),
 * lists as Word lists, tables as Word tables, links as hyperlinks, code as shaded monospace blocks. It defines the
 * styles and list numbering of the document once; the chat export ({@code ChatDocxWriter}) and the page export
 * ({@code DocxExportFormat}) write their own frame around it. Unknown Markdown nodes keep their readable text.
 *
 * <p>Figures (#132): with {@link ExportGraphics} an image that resolves is embedded as a picture where it stands
 * (scaled to the text width), its caption below; a Mermaid fence becomes the drawn diagram. A figure that does not
 * resolve leaves a visible notice and is reported by the graphics. Without graphics images stay text.</p>
 */
public final class MarkdownDocxRenderer {

    public static final String STYLE_TITLE = "Title";
    public static final String STYLE_HEADING = "Heading";
    /** The deepest heading style Word offers. */
    public static final int MAX_HEADING_LEVEL = 9;
    public static final String LINK_COLOR = "1F5FAF";
    public static final String MUTED_COLOR = "6B6B6B";
    public static final String RULE_COLOR = "BFC7D0";
    public static final int SMALL_POINTS = 9;
    public static final int INDENT_STEP = 360;

    private static final String CODE_FONT = "Consolas";
    private static final String BODY_FONT = "Calibri";
    private static final String HEADER_FILL = "EAF0F6";
    private static final String CODE_FILL = "F4F5F7";
    private static final int BODY_HALF_POINTS = 22;
    private static final int CODE_POINTS = 9;
    private static final int MAX_LEVELS = 9;
    private static final List<String> BULLETS = java.util.Collections.unmodifiableList(
            Arrays.asList("•", "◦", "▪"));
    private static final int[] HEADING_HALF_POINTS = {32, 28, 26, 24, 22, 22};
    /** The text width of Word's default page (Letter or A4 with its default margins), a little less. */
    private static final double MAX_PICTURE_POINTS = 450d;
    private static final double MAX_PICTURE_HEIGHT_POINTS = 620d;
    private static final double PIXELS_TO_POINTS = 0.75d;
    private static final String MISSING = "Abbildung nicht verfügbar";

    private final XWPFDocument document;
    private final Parser parser;
    private final BigInteger bulletNumId;
    private final BigInteger decimalAbstractId;
    private final XWPFNumbering numbering;
    private final ExportGraphics graphics;
    private int pictures;

    /** Wohin Blöcke gerade geschrieben werden: der Dokumentkörper oder eine Tabellenzelle. */
    private XWPFTableCell cell;
    private boolean cellParagraphUsed;

    /** Define the styles and list numbering of {@code document} and write into it. */
    public MarkdownDocxRenderer(XWPFDocument document) {
        this(document, null);
    }

    /** As {@link #MarkdownDocxRenderer(XWPFDocument)}, embedding the figures {@code graphics} resolves. */
    public MarkdownDocxRenderer(XWPFDocument document, ExportGraphics graphics) {
        this.document = document;
        this.graphics = graphics;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create(), StrikethroughExtension.create(),
                AutolinkExtension.create()));
        this.parser = Parser.builder(options).build();
        defineStyles(document);
        this.numbering = document.createNumbering();
        BigInteger bulletAbstract = numbering.addAbstractNum(new XWPFAbstractNum(listDefinition(0, true)));
        this.bulletNumId = numbering.addNum(bulletAbstract);
        this.decimalAbstractId = numbering.addAbstractNum(new XWPFAbstractNum(listDefinition(1, false)));
    }

    // -------------------------------------------------------------- Markdown

    /** Write {@code text} as Word blocks; {@code muted} greys it out like a notice. */
    public void markdown(String text, boolean muted) {
        markdown(text, muted, 0);
    }

    /**
     * Write {@code text} as Word blocks with every heading {@code headingShift} levels deeper, so the headings of
     * a page stay below the chapter heading it is written under; a heading deeper than
     * {@link #MAX_HEADING_LEVEL} becomes a bold paragraph.
     */
    public void markdown(String text, boolean muted, int headingShift) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        Node root = parser.parse(text.replace("\r\n", "\n"));
        Context context = new Context(muted);
        context.headingShift = Math.max(0, headingShift);
        blocks(root, context);
    }

    private void blocks(Node container, Context context) {
        for (Node child = container.getFirstChild(); child != null; child = child.getNext()) {
            block(child, context);
        }
    }

    private void block(Node node, Context context) {
        if (node instanceof HtmlCommentBlock) {
            return; // wie in der Oberfläche: Kommentare bleiben unsichtbar
        }
        if (node instanceof Heading) {
            Heading heading = (Heading) node;
            XWPFParagraph paragraph = paragraph();
            int level = Math.max(1, heading.getLevel()) + context.headingShift;
            if (cell == null && context.quoteDepth == 0 && context.listDepth == 0 && level <= MAX_HEADING_LEVEL) {
                paragraph.setStyle(STYLE_HEADING + level);
                inlines(paragraph, heading, new Format(), context);
            } else {
                context.decorate(paragraph);
                Format bold = new Format();
                bold.bold = true;
                inlines(paragraph, heading, bold, context);
            }
            return;
        }
        if (node instanceof Paragraph) {
            XWPFParagraph paragraph = context.pendingListParagraph != null ? context.takeListParagraph()
                    : paragraph();
            if (paragraph.getNumID() == null) {
                context.decorate(paragraph);
            }
            inlines(paragraph, node, new Format(), context);
            return;
        }
        if (node instanceof FencedCodeBlock) {
            FencedCodeBlock fenced = (FencedCodeBlock) node;
            if (graphics != null && PdfExportWriter.isMermaid(fenced.getInfo().toString())) {
                ExportGraphics.Graphic diagram = graphics.mermaid(fenced.getContentChars().toString());
                if (diagram != null) {
                    XWPFParagraph paragraph = paragraph();
                    context.decorate(paragraph);
                    picture(paragraph, diagram, "Mermaid-Diagramm");
                    return;
                }
            }
            codeBlock(fenced.getInfo().toString().trim(), fenced.getContentChars().toString(), context);
            return;
        }
        if (node instanceof IndentedCodeBlock) {
            codeBlock("", ((IndentedCodeBlock) node).getContentChars().toString(), context);
            return;
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            list(node, context);
            return;
        }
        if (node instanceof BlockQuote) {
            context.quoteDepth++;
            try {
                blocks(node, context);
            } finally {
                context.quoteDepth--;
            }
            return;
        }
        if (node instanceof TableBlock) {
            table((TableBlock) node, context);
            return;
        }
        if (node instanceof ThematicBreak) {
            XWPFParagraph rule = paragraph();
            context.decorate(rule);
            rule.setBorderBottom(Borders.SINGLE);
            borderColor(rule, RULE_COLOR);
            return;
        }
        if (node instanceof HtmlBlock) {
            XWPFParagraph paragraph = paragraph();
            context.decorate(paragraph);
            text(paragraph, node.getChars().toString().trim(), new Format(), context);
            return;
        }
        if (node.hasChildren()) {
            blocks(node, context);
            return;
        }
        String rest = node.getChars().toString().trim();
        if (!rest.isEmpty()) {
            XWPFParagraph paragraph = paragraph();
            context.decorate(paragraph);
            text(paragraph, rest, new Format(), context);
        }
    }

    private void list(Node list, Context context) {
        boolean ordered = list instanceof OrderedList;
        int level = Math.min(context.listDepth, MAX_LEVELS - 1);
        BigInteger numId = bulletNumId;
        if (ordered) {
            numId = numbering.addNum(decimalAbstractId);
            CTNumLvl override = numbering.getNum(numId).getCTNum().addNewLvlOverride();
            override.setIlvl(BigInteger.valueOf(level));
            override.addNewStartOverride().setVal(BigInteger.valueOf(Math.max(0,
                    ((OrderedList) list).getStartNumber())));
        }
        context.listDepth++;
        try {
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                if (!(item instanceof ListItem)) {
                    block(item, context);
                    continue;
                }
                context.pendingListParagraph = listParagraph(numId, level);
                context.decorateQuote(context.pendingListParagraph);
                blocks(item, context);
                context.pendingListParagraph = null; // leerer Eintrag: der Absatz bleibt leer stehen
            }
        } finally {
            context.listDepth--;
        }
    }

    private void codeBlock(String info, String code, Context context) {
        String language = info.isEmpty() ? "" : info.split("\\s+")[0];
        String body = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        if (!language.isEmpty()) {
            XWPFParagraph caption = paragraph();
            context.decorate(caption);
            caption.setSpacingAfter(0);
            caption.setKeepNext(true);
            small(caption, language, MUTED_COLOR);
        }
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            XWPFParagraph line = paragraph();
            context.decorate(line);
            line.setSpacingBefore(0);
            line.setSpacingAfter(i == lines.length - 1 ? 160 : 0);
            line.setSpacingBetween(1.0);
            line.setBorderLeft(Borders.SINGLE);
            line.setBorderRight(Borders.SINGLE);
            if (i == 0) {
                line.setBorderTop(Borders.SINGLE);
            }
            if (i == lines.length - 1) {
                line.setBorderBottom(Borders.SINGLE);
            }
            if (i < lines.length - 1) {
                line.setKeepNext(true);
            }
            borderColor(line, RULE_COLOR);
            shade(line, CODE_FILL);
            XWPFRun run = line.createRun();
            run.setFontFamily(CODE_FONT);
            run.setFontSize(CODE_POINTS);
            run.setText(clean(lines[i].replace("\t", "    ")));
        }
    }

    private void table(TableBlock block, Context context) {
        List<TableRow> rows = new ArrayList<TableRow>();
        List<Boolean> header = new ArrayList<Boolean>();
        int columns = 0;
        for (Node section = block.getFirstChild(); section != null; section = section.getNext()) {
            if (section instanceof TableSeparator) {
                continue; // die Zeile |---|:---:| trägt nur die Ausrichtung
            }
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof TableRow) {
                    rows.add((TableRow) row);
                    header.add(section instanceof TableHead);
                    int count = 0;
                    for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
                        if (c instanceof TableCell) {
                            count++;
                        }
                    }
                    columns = Math.max(columns, count);
                }
            }
        }
        if (rows.isEmpty() || columns == 0) {
            return;
        }
        if (cell != null) {
            // Tabelle in einer Tabellenzelle: als Zeilen mit Trennstrichen, der Text bleibt erhalten.
            for (TableRow row : rows) {
                XWPFParagraph paragraph = paragraph();
                StringBuilder line = new StringBuilder();
                for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof TableCell) {
                        if (line.length() > 0) {
                            line.append(" | ");
                        }
                        line.append(plain(c));
                    }
                }
                text(paragraph, line.toString(), new Format(), context);
            }
            return;
        }
        paragraph().setSpacingAfter(0); // Abstand vor der Tabelle, sonst klebt sie am vorigen Block
        XWPFTable table = document.createTable(rows.size(), columns);
        table.setWidth("100%");
        table.setCellMargins(40, 80, 40, 80);
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow tableRow = table.getRow(r);
            boolean isHeader = header.get(r);
            if (isHeader) {
                tableRow.setRepeatHeader(true);
            }
            int column = 0;
            for (Node c = rows.get(r).getFirstChild(); c != null; c = c.getNext()) {
                if (!(c instanceof TableCell)) {
                    continue;
                }
                TableCell source = (TableCell) c;
                XWPFTableCell target = tableRow.getCell(column++);
                if (isHeader) {
                    target.setColor(HEADER_FILL);
                }
                cell = target;
                cellParagraphUsed = false;
                try {
                    XWPFParagraph paragraph = paragraph();
                    paragraph.setSpacingAfter(0);
                    paragraph.setAlignment(alignment(source.getAlignment()));
                    Format format = new Format();
                    format.bold = isHeader;
                    inlines(paragraph, source, format, context);
                } finally {
                    cell = null;
                }
            }
        }
        // Abstand nach der Tabelle, sonst klebt der nächste Absatz an ihr.
        paragraph().setSpacingAfter(0);
    }

    private ParagraphAlignment alignment(TableCell.Alignment alignment) {
        if (alignment == TableCell.Alignment.CENTER) {
            return ParagraphAlignment.CENTER;
        }
        return alignment == TableCell.Alignment.RIGHT ? ParagraphAlignment.RIGHT : ParagraphAlignment.LEFT;
    }

    // -------------------------------------------------------------- Inline

    private void inlines(XWPFParagraph paragraph, Node parent, Format format, Context context) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            inline(paragraph, node, format, context);
        }
    }

    private void inline(XWPFParagraph paragraph, Node node, Format format, Context context) {
        if (node instanceof Text) {
            text(paragraph, node.getChars().unescape(), format, context);
        } else if (node instanceof StrongEmphasis) {
            Format inner = format.copy();
            inner.bold = true;
            inlines(paragraph, node, inner, context);
        } else if (node instanceof Emphasis) {
            Format inner = format.copy();
            inner.italic = true;
            inlines(paragraph, node, inner, context);
        } else if (node instanceof Strikethrough) {
            Format inner = format.copy();
            inner.strike = true;
            inlines(paragraph, node, inner, context);
        } else if (node instanceof Code) {
            Format inner = format.copy();
            inner.code = true;
            text(paragraph, ((Code) node).getText().toString(), inner, context);
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            // wie in der Oberfläche: die Zeilenstruktur der Antwort bleibt
            paragraph.createRun().addBreak();
        } else if (node instanceof Link && graphics != null && node.getFirstChild() instanceof Image
                && node.getFirstChild() == node.getLastChild()) {
            inline(paragraph, node.getFirstChild(), format, context); // [![Bild](…)](…): the picture
        } else if (node instanceof Link) {
            Link link = (Link) node;
            link(paragraph, plain(link), link.getUrl().unescape(), format, context);
        } else if (node instanceof AutoLink) {
            String url = ((AutoLink) node).getUrl().unescape();
            link(paragraph, url, url, format, context);
        } else if (node instanceof MailLink) {
            String mail = ((MailLink) node).getText().toString();
            link(paragraph, mail, "mailto:" + mail, format, context);
        } else if (node instanceof Image) {
            Image image = (Image) node;
            String alt = plain(image);
            if (graphics == null) {
                text(paragraph, "[Bild" + (alt.isEmpty() ? "" : ": " + alt) + "]", format, context);
                return;
            }
            ExportGraphics.Graphic graphic = graphics.image(image.getUrl().unescape(), alt);
            if (graphic == null) {
                Format missing = format.copy();
                missing.italic = true;
                text(paragraph, "[" + MISSING + (alt.isEmpty() ? "" : ": " + alt) + "]", missing, context);
                return;
            }
            if (!paragraph.getRuns().isEmpty()) {
                paragraph.createRun().addBreak();
            }
            picture(paragraph, graphic, alt.isEmpty() ? "Abbildung" : alt);
            if (!alt.isEmpty()) {
                paragraph.createRun().addBreak();
                XWPFRun caption = paragraph.createRun();
                caption.setFontSize(SMALL_POINTS);
                caption.setItalic(true);
                caption.setColor(MUTED_COLOR);
                caption.setText(clean(alt));
            }
            if (node.getNext() != null) {
                paragraph.createRun().addBreak();
            }
        } else if (node instanceof RefNode) {
            reference(paragraph, (RefNode) node, format, context);
        } else if (node instanceof HtmlInlineComment) {
            return;
        } else if (node.hasChildren()) {
            inlines(paragraph, node, format, context);
        } else {
            text(paragraph, node.getChars().unescape(), format, context);
        }
    }

    /** {@code [Text][ref]}: mit Definition ein Link, ohne bleibt der Quelltext, wie er dasteht. */
    private void reference(XWPFParagraph paragraph, RefNode node, Format format, Context context) {
        Reference reference = node.isDefined() && node.getDocument() != null
                ? node.getReferenceNode(node.getDocument()) : null;
        if (reference == null) {
            text(paragraph, node.getChars().toString(), format, context);
        } else if (node instanceof com.vladsch.flexmark.ast.ImageRef) {
            text(paragraph, "[Bild: " + plain((Node) node) + "]", format, context);
        } else {
            link(paragraph, plain((Node) node), reference.getUrl().unescape(), format, context);
        }
    }

    private void link(XWPFParagraph paragraph, String label, String url, Format format, Context context) {
        String shown = label.isEmpty() ? url : label;
        if (isWebLink(url)) {
            XWPFRun run = hyperlink(paragraph, shown, url, format.bold);
            apply(run, format, context, false);
        } else {
            text(paragraph, shown, format, context);
        }
    }

    public XWPFRun hyperlink(XWPFParagraph paragraph, String label, String url, boolean bold) {
        XWPFRun run = paragraph.createHyperlinkRun(url.trim());
        run.setText(clean(label));
        run.setColor(LINK_COLOR);
        run.setUnderline(org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE);
        run.setBold(bold);
        return run;
    }

    private void text(XWPFParagraph paragraph, String value, Format format, Context context) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String[] lines = value.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                paragraph.createRun().addBreak();
            }
            if (lines[i].isEmpty()) {
                continue;
            }
            XWPFRun run = paragraph.createRun();
            run.setText(clean(lines[i]));
            apply(run, format, context, true);
        }
    }

    private void apply(XWPFRun run, Format format, Context context, boolean color) {
        if (format.bold) {
            run.setBold(true);
        }
        if (format.italic || context.quoteDepth > 0) {
            run.setItalic(true);
        }
        if (format.strike) {
            run.setStrikeThrough(true);
        }
        if (format.code) {
            run.setFontFamily(CODE_FONT);
            shade(run, CODE_FILL);
        }
        if (color && (context.muted || context.quoteDepth > 0)) {
            run.setColor(MUTED_COLOR);
        }
    }

    /** Embed {@code graphic} as an inline picture, no wider than the text and no higher than a page. */
    private void picture(XWPFParagraph paragraph, ExportGraphics.Graphic graphic, String name) {
        double width = graphic.width() * PIXELS_TO_POINTS;
        double height = graphic.height() * PIXELS_TO_POINTS;
        double scale = Math.min(1d, Math.min(MAX_PICTURE_POINTS / width, MAX_PICTURE_HEIGHT_POINTS / height));
        XWPFRun run = paragraph.createRun();
        try {
            run.addPicture(new ByteArrayInputStream(graphic.png()), org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                    "abbildung-" + (++pictures) + ".png", org.apache.poi.util.Units.toEMU(width * scale),
                    org.apache.poi.util.Units.toEMU(height * scale));
        } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            run.setText(clean("[" + MISSING + ": " + name + "]"));
        }
    }

    // -------------------------------------------------------------- Bausteine

    /** Ein neuer Absatz im Dokument oder in der aktuellen Tabellenzelle. */
    public XWPFParagraph paragraph() {
        if (cell != null) {
            if (!cellParagraphUsed && !cell.getParagraphs().isEmpty()) {
                cellParagraphUsed = true;
                return cell.getParagraphs().get(0);
            }
            return cell.addParagraph();
        }
        return document.createParagraph();
    }

    /** A paragraph of the bullet list at {@code level} (0 = top). */
    public XWPFParagraph bulletParagraph(int level) {
        return listParagraph(bulletNumId, Math.max(0, Math.min(level, MAX_LEVELS - 1)));
    }

    private XWPFParagraph listParagraph(BigInteger numId, int level) {
        XWPFParagraph paragraph = paragraph();
        paragraph.setNumID(numId);
        paragraph.setNumILvl(BigInteger.valueOf(level));
        paragraph.setSpacingAfter(40);
        return paragraph;
    }

    public void small(XWPFParagraph paragraph, String text, String color) {
        XWPFRun run = paragraph.createRun();
        run.setFontSize(SMALL_POINTS);
        run.setColor(color);
        run.setText(clean(text));
    }

    private void shade(XWPFParagraph paragraph, String fill) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd = ppr.isSetShd() ? ppr.getShd()
                : ppr.addNewShd();
        shd.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(fill);
    }

    private void shade(XWPFRun run, String fill) {
        CTRPr rpr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd = rpr.addNewShd();
        shd.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(fill);
    }

    /** Wo ein Block gerade steht: in Zitaten (eingerückt, grau, kursiv), in Listen, in einem Hinweis. */
    private static final class Context {
        final boolean muted;
        int quoteDepth;
        int listDepth;
        /** Levels every heading of the text goes deeper. */
        int headingShift;
        /** Der nummerierte Absatz eines Listeneintrags, den sein erster Absatz übernimmt. */
        XWPFParagraph pendingListParagraph;

        Context(boolean muted) {
            this.muted = muted;
        }

        XWPFParagraph takeListParagraph() {
            XWPFParagraph paragraph = pendingListParagraph;
            pendingListParagraph = null;
            return paragraph;
        }

        void decorate(XWPFParagraph paragraph) {
            int indent = listDepth * INDENT_STEP + quoteDepth * INDENT_STEP;
            if (indent > 0) {
                paragraph.setIndentationLeft(indent);
            }
            decorateQuote(paragraph);
        }

        void decorateQuote(XWPFParagraph paragraph) {
            if (quoteDepth > 0) {
                paragraph.setBorderLeft(Borders.SINGLE);
                borderColor(paragraph, RULE_COLOR);
                if (paragraph.getNumID() != null) {
                    paragraph.setIndentationLeft((listDepth + quoteDepth) * INDENT_STEP);
                    paragraph.setIndentationHanging(INDENT_STEP);
                }
            }
        }
    }

    private static final class Format {
        boolean bold;
        boolean italic;
        boolean strike;
        boolean code;

        Format copy() {
            Format copy = new Format();
            copy.bold = bold;
            copy.italic = italic;
            copy.strike = strike;
            copy.code = code;
            return copy;
        }
    }

    // ------------------------------------------------------------------ Formatvorlagen und Listen

    /** Standard (Calibri 11), Titel und Überschrift 1–9 als echte Word-Überschriften (Navigationsbereich). */
    private static void defineStyles(XWPFDocument document) {
        XWPFStyles styles = document.createStyles();
        CTFonts fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(BODY_FONT);
        fonts.setHAnsi(BODY_FONT);
        fonts.setCs(BODY_FONT);
        fonts.setEastAsia(BODY_FONT);
        styles.setDefaultFonts(fonts);

        CTStyle normal = style("Normal", "Normal", true);
        CTRPr normalRun = normal.addNewRPr();
        normalRun.addNewSz().setVal(BigInteger.valueOf(BODY_HALF_POINTS));
        normalRun.addNewSzCs().setVal(BigInteger.valueOf(BODY_HALF_POINTS));
        CTSpacing normalSpacing = normal.addNewPPr().addNewSpacing();
        normalSpacing.setAfter(BigInteger.valueOf(120));
        normalSpacing.setLine(BigInteger.valueOf(264));
        add(styles, normal);

        CTStyle title = style(STYLE_TITLE, "Title", false);
        title.addNewBasedOn().setVal("Normal");
        title.addNewNext().setVal("Normal");
        CTPPrGeneral titleParagraph = title.addNewPPr();
        titleParagraph.addNewSpacing().setAfter(BigInteger.valueOf(60));
        CTRPr titleRun = title.addNewRPr();
        titleRun.addNewSz().setVal(BigInteger.valueOf(44));
        titleRun.addNewSzCs().setVal(BigInteger.valueOf(44));
        titleRun.addNewColor().setVal("1F2A37");
        add(styles, title);

        for (int level = 1; level <= MAX_HEADING_LEVEL; level++) {
            CTStyle heading = style(STYLE_HEADING + level, "heading " + level, false);
            heading.addNewBasedOn().setVal("Normal");
            heading.addNewNext().setVal("Normal");
            heading.addNewUiPriority().setVal(BigInteger.valueOf(9));
            heading.addNewQFormat();
            CTPPrGeneral paragraph = heading.addNewPPr();
            paragraph.addNewKeepNext();
            CTSpacing spacing = paragraph.addNewSpacing();
            spacing.setBefore(BigInteger.valueOf(level <= 2 ? 240 : 180));
            spacing.setAfter(BigInteger.valueOf(80));
            paragraph.addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1));
            CTRPr run = heading.addNewRPr();
            run.addNewB();
            if (level >= 5) {
                run.addNewI();
            }
            int size = HEADING_HALF_POINTS[Math.min(level, HEADING_HALF_POINTS.length) - 1];
            run.addNewSz().setVal(BigInteger.valueOf(size));
            run.addNewSzCs().setVal(BigInteger.valueOf(size));
            run.addNewColor().setVal("1F2A37");
            add(styles, heading);
        }
    }

    private static CTStyle style(String id, String name, boolean isDefault) {
        CTStyle style = CTStyle.Factory.newInstance();
        style.setStyleId(id);
        style.setType(STStyleType.PARAGRAPH);
        style.addNewName().setVal(name);
        if (isDefault) {
            style.setDefault(true);
        }
        return style;
    }

    private static void add(XWPFStyles styles, CTStyle style) {
        XWPFStyle wrapped = new XWPFStyle(style, styles);
        styles.addStyle(wrapped);
    }

    private static CTAbstractNum listDefinition(int id, boolean bullet) {
        CTAbstractNum definition = CTAbstractNum.Factory.newInstance();
        definition.setAbstractNumId(BigInteger.valueOf(id));
        for (int level = 0; level < MAX_LEVELS; level++) {
            CTLvl lvl = definition.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(level));
            lvl.addNewStart().setVal(BigInteger.ONE);
            if (bullet) {
                lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
                lvl.addNewLvlText().setVal(BULLETS.get(level % BULLETS.size()));
            } else {
                lvl.addNewNumFmt().setVal(level % 3 == 0 ? STNumberFormat.DECIMAL
                        : level % 3 == 1 ? STNumberFormat.LOWER_LETTER : STNumberFormat.LOWER_ROMAN);
                lvl.addNewLvlText().setVal("%" + (level + 1) + ".");
            }
            CTInd indent = lvl.addNewPPr().addNewInd();
            indent.setLeft(BigInteger.valueOf((long) INDENT_STEP * (level + 1)));
            indent.setHanging(BigInteger.valueOf(INDENT_STEP));
        }
        return definition;
    }

    // ------------------------------------------------------------------ Hilfen

    /** Färbt die gesetzten Absatzrahmen dezent statt schwarz. */
    public static void borderColor(XWPFParagraph paragraph, String color) {
        if (!paragraph.getCTP().isSetPPr() || !paragraph.getCTP().getPPr().isSetPBdr()) {
            return;
        }
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPBdr borders =
                paragraph.getCTP().getPPr().getPBdr();
        for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder border : Arrays.asList(
                borders.getTop(), borders.getBottom(), borders.getLeft(), borders.getRight())) {
            if (border != null) {
                border.setColor(color);
            }
        }
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

    /** Nur http, https und mailto werden echte Hyperlinks (wie DesktopLinkOpener in der Oberfläche). */
    public static boolean isWebLink(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    /** Entfernt Zeichen, die in XML 1.0 nicht vorkommen dürfen (Steuerzeichen außer Tab). */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean bad = c < 0x20 && c != '\t' || c == 0xFFFE || c == 0xFFFF
                    || Character.isSurrogate(c) && !validSurrogate(text, i);
            if (bad) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                    out.append(text, 0, i);
                }
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }

    private static boolean validSurrogate(String text, int i) {
        char c = text.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
    }
}
