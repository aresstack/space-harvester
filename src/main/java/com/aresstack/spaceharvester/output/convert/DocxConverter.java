package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
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
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Word (#30): alle Seiten eines Exports in einer Datei {@code pages.docx} (Office Open XML), linear in Baumreihenfolge.
 * Jede Seite beginnt mit einer Überschrift nach ihrer Tiefe (Formatvorlagen Überschrift 1 bis 6, im Navigationsbereich
 * sichtbar) und einer Textmarke {@code page_<id>}; Verweise auf Seiten desselben Exports springen dorthin.
 *
 * <p>Der Inhalt kommt wie bei {@link MarkdownConverter} aus dem Storage-Format, mit denselben Formatverlusten: Bilder
 * und Anhänge bleiben Verweise im Text, Diagramme ohne Textquelle erscheinen als Hinweis. Die Datei wird ohne
 * Fremdbibliothek geschrieben, Seite für Seite in den Strom; Zeitstempel im Archiv sind fest, damit gleicher Input
 * dieselben Bytes ergibt.
 */
public final class DocxConverter implements ExportConverter {

    public static final String FORMAT = "docx";
    public static final String VERSION = "docx/2";

    /** Fester Zeitstempel der Archiveinträge: 1980-01-01, der früheste, den ZIP kennt. */
    private static final long ZIP_TIME = 315532800000L;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String MONO = "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>";

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
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override
    public String fileName() {
        return "pages.docx";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) throws IOException {
        StorageParser parser = new StorageParser(PageLinks.of(input.headers(), input.spaceKey()));
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        Body body = new Body();
        entry(zip, "[Content_Types].xml", CONTENT_TYPES);
        entry(zip, "_rels/.rels", PACKAGE_RELS);
        entry(zip, "word/styles.xml", STYLES);
        zip.putNextEntry(fixed("word/document.xml"));
        Writer document = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        document.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:document xmlns:w=\"" + W
                + "\" xmlns:r=\"" + R + "\"><w:body>");
        document.write(body.paragraph("Title", run("Space " + input.spaceKey() + " (" + input.sourceId() + ")",
                "")));
        try {
            input.forEachPage(page -> {
                try {
                    document.write(body.page(page, parser));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        document.write("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" "
                + "w:bottom=\"1134\" w:left=\"1134\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>");
        document.flush();
        zip.closeEntry();
        entry(zip, "word/numbering.xml", body.numbering());
        entry(zip, "word/_rels/document.xml.rels", body.relationships());
        zip.finish();
    }

    private static ZipEntry fixed(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ZIP_TIME);
        return entry;
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(fixed(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Der Dokumentinhalt; merkt sich Hyperlinks und Listen für die Teile, die nach ihm geschrieben werden. */
    private static final class Body {

        private final Map<String, String> hyperlinks = new LinkedHashMap<>();
        /** Je nummerierter Liste eine eigene Nummerierung, damit jede bei 1 beginnt. */
        private int orderedLists;
        private int bookmarks;

        String page(ConvertiblePage page, StorageParser parser) {
            PageMetadata metadata = page.header().metadata();
            int level = Math.min(6, page.depth() + 1);
            String title = metadata.title() == null || metadata.title().isBlank() ? "Seite " + page.pageId()
                    : metadata.title();
            int id = bookmarks++;
            String bookmark = "<w:bookmarkStart w:id=\"" + id + "\" w:name=\"" + bookmark(page.pageId()) + "\"/>"
                    + run(title, "") + "<w:bookmarkEnd w:id=\"" + id + "\"/>";
            StringBuilder xml = new StringBuilder(paragraph("Heading" + level, bookmark));
            StringBuilder facts = new StringBuilder(run("Seite " + page.pageId(), ""));
            if (metadata.version() != null) {
                facts.append(run(" · Version " + metadata.version(), ""));
            }
            if (metadata.url() != null) {
                facts.append(run(" · ", "")).append(hyperlink(metadata.url().toString(), "In Confluence öffnen"));
            }
            xml.append(paragraph("PageFacts", facts.toString()));
            List<Block> blocks = PageBody.storage(page.snapshot().payload()).map(parser::parse)
                    .orElse(List.of(new Note("Die Payload enthält keinen Inhalt im Storage-Format")));
            blocks(blocks, level, 0, null, xml);
            return xml.toString();
        }

        /** @param style Formatvorlage für Absätze ohne eigene, etwa {@code Quote} in einem Kasten */
        private void blocks(List<Block> blocks, int headingOffset, int indent, String style, StringBuilder xml) {
            for (Block block : blocks) {
                block(block, headingOffset, indent, style, xml);
            }
        }

        private void block(Block block, int headingOffset, int indent, String style, StringBuilder xml) {
            switch (block) {
                case Heading heading -> xml.append(paragraph(
                        "Heading" + Math.min(6, headingOffset + heading.level()), inlines(heading.content())));
                case Paragraph paragraph -> xml.append(paragraph(style, indent, null, inlines(paragraph.content())));
                case ListBlock list -> list(list, headingOffset, indent, style, xml);
                case Table table -> table(table, headingOffset, xml);
                case CodeBlock code -> {
                    StringBuilder runs = new StringBuilder();
                    String[] lines = code.code().stripTrailing().split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (i > 0) {
                            runs.append("<w:r><w:br/></w:r>");
                        }
                        runs.append(run(lines[i], MONO));
                    }
                    xml.append(paragraph("Code", indent, null, runs.toString()));
                }
                case Panel panel -> {
                    xml.append(paragraph("Quote", indent, null, run(panel.label(), "<w:b/>")));
                    blocks(panel.content(), headingOffset, indent, "Quote", xml);
                }
                case Rule rule -> xml.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" "
                        + "w:space=\"1\" w:color=\"999999\"/></w:pBdr></w:pPr></w:p>");
                case Note note -> xml.append(paragraph("ExportNote", indent, null,
                        run("[Export: " + note.text() + "]", "")));
            }
        }

        private void list(ListBlock list, int headingOffset, int indent, String style, StringBuilder xml) {
            int numId = list.ordered() ? 2 + orderedLists++ : 1;
            int level = Math.min(8, indent);
            for (int i = 0; i < list.items().size(); i++) {
                List<Block> item = list.items().get(i);
                String prefix = list.checked() == null ? "" : list.checked().get(i) ? "☑ " : "☐ ";
                boolean first = true;
                for (Block block : item) {
                    if (first && block instanceof Paragraph paragraph) {
                        xml.append(paragraph(style, indent, new int[] {numId, level},
                                run(prefix, "") + inlines(paragraph.content())));
                    } else {
                        if (first) {
                            xml.append(paragraph(style, indent, new int[] {numId, level}, run(prefix, "")));
                        }
                        block(block, headingOffset, indent + 1, style, xml);
                    }
                    first = false;
                }
                if (item.isEmpty()) {
                    xml.append(paragraph(style, indent, new int[] {numId, level}, run(prefix, "")));
                }
            }
        }

        private void table(Table table, int headingOffset, StringBuilder xml) {
            int columns = table.rows().stream().mapToInt(List::size).max().orElse(0);
            if (columns == 0) {
                return;
            }
            int width = 9638 / columns;
            xml.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>"
                    + "</w:tblPr><w:tblGrid>");
            xml.append(("<w:gridCol w:w=\"" + width + "\"/>").repeat(columns)).append("</w:tblGrid>");
            for (int r = 0; r < table.rows().size(); r++) {
                List<List<Block>> row = table.rows().get(r);
                boolean header = r == 0 && table.headerRow();
                xml.append("<w:tr>").append(header ? "<w:trPr><w:tblHeader/></w:trPr>" : "");
                for (int c = 0; c < columns; c++) {
                    xml.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>")
                            .append(header ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E7E6E6\"/>" : "")
                            .append("</w:tcPr>");
                    StringBuilder cell = new StringBuilder();
                    if (c < row.size()) {
                        blocks(row.get(c), headingOffset, 0, null, cell);
                    }
                    // Eine Zelle braucht mindestens einen Absatz.
                    xml.append(cell.isEmpty() ? "<w:p/>" : cell.toString()).append("</w:tc>");
                }
                xml.append("</w:tr>");
            }
            xml.append("</w:tbl><w:p/>");
        }

        String paragraph(String style, String runs) {
            return paragraph(style, 0, null, runs);
        }

        /** @param numbering Nummerierung und Ebene, oder {@code null} */
        String paragraph(String style, int indent, int[] numbering, String runs) {
            StringBuilder properties = new StringBuilder();
            if (style != null) {
                properties.append("<w:pStyle w:val=\"").append(style).append("\"/>");
            }
            if (numbering != null) {
                properties.append("<w:numPr><w:ilvl w:val=\"").append(numbering[1]).append("\"/><w:numId w:val=\"")
                        .append(numbering[0]).append("\"/></w:numPr>");
            } else if (indent > 0) {
                properties.append("<w:ind w:left=\"").append(360 * indent).append("\"/>");
            }
            return "<w:p>" + (properties.isEmpty() ? "" : "<w:pPr>" + properties + "</w:pPr>") + runs + "</w:p>";
        }

        private String inlines(List<Inline> content) {
            StringBuilder runs = new StringBuilder();
            for (Inline inline : content) {
                switch (inline) {
                    case Text text -> runs.append(run(text.text(), (text.bold() ? "<w:b/>" : "")
                            + (text.italic() ? "<w:i/>" : "") + (text.code() ? MONO : "")));
                    case Link link -> {
                        String label = link.text().isEmpty() && link.target() != null ? link.target() : link.text();
                        runs.append(link.target() == null ? run(label, "") : hyperlink(link.target(), label));
                    }
                    case ImageRef image -> runs.append(run("[Bild: " + image.alt()
                            + (image.reference().isEmpty() ? "" : " – " + image.reference()) + "]", "<w:i/>"));
                    case LineBreak lineBreak -> runs.append("<w:r><w:br/></w:r>");
                }
            }
            return runs.toString();
        }

        private String hyperlink(String target, String label) {
            String runs = run(label, "<w:rStyle w:val=\"Hyperlink\"/>");
            if (target.startsWith("#page-")) {
                return "<w:hyperlink w:anchor=\"" + bookmark(target.substring("#page-".length())) + "\">" + runs
                        + "</w:hyperlink>";
            }
            if (!target.matches("(?i)(https?|mailto):.*")) {
                // Anhänge und andere Ziele ohne Adresse bleiben Text.
                return run(label + " (" + target + ")", "");
            }
            String id = hyperlinks.computeIfAbsent(target, url -> "rIdLink" + (hyperlinks.size() + 1));
            return "<w:hyperlink r:id=\"" + id + "\">" + runs + "</w:hyperlink>";
        }

        String numbering() {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<w:numbering xmlns:w=\"" + W + "\">");
            xml.append(abstractNumbering(0, false)).append(abstractNumbering(1, true));
            xml.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>");
            for (int i = 0; i < orderedLists; i++) {
                xml.append("<w:num w:numId=\"").append(2 + i).append("\"><w:abstractNumId w:val=\"1\"/>")
                        .append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>");
            }
            return xml.append("</w:numbering>").toString();
        }

        private static String abstractNumbering(int id, boolean ordered) {
            StringBuilder xml = new StringBuilder("<w:abstractNum w:abstractNumId=\"" + id + "\">"
                    + "<w:multiLevelType w:val=\"hybridMultilevel\"/>");
            for (int level = 0; level < 9; level++) {
                xml.append("<w:lvl w:ilvl=\"").append(level).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"")
                        .append(ordered ? "decimal" : "bullet").append("\"/><w:lvlText w:val=\"")
                        .append(ordered ? "%" + (level + 1) + "." : level % 2 == 0 ? "•" : "◦")
                        .append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"")
                        .append(720 + 360 * level).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
            }
            return xml.append("</w:abstractNum>").toString();
        }

        String relationships() {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rIdStyles\" Type=\"" + R + "/styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rIdNumbering\" Type=\"" + R + "/numbering\" Target=\"numbering.xml\"/>");
            for (Map.Entry<String, String> link : hyperlinks.entrySet()) {
                xml.append("<Relationship Id=\"").append(link.getValue()).append("\" Type=\"").append(R)
                        .append("/hyperlink\" Target=\"").append(xml(link.getKey()))
                        .append("\" TargetMode=\"External\"/>");
            }
            return xml.append("</Relationships>").toString();
        }
    }

    /** Textmarke einer Seite: Word erlaubt Buchstaben, Ziffern und Unterstrich, höchstens 40 Zeichen. */
    static String bookmark(String pageId) {
        String name = "page_" + pageId.replaceAll("[^A-Za-z0-9_]", "_");
        return name.length() <= 40 ? name : name.substring(0, 40);
    }

    private static String run(String text, String properties) {
        if (text.isEmpty()) {
            return "";
        }
        return "<w:r>" + (properties.isEmpty() ? "" : "<w:rPr>" + properties + "</w:rPr>")
                + "<w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r>";
    }

    /** Maskiert XML und entfernt Zeichen, die XML 1.0 nicht erlaubt. */
    static String xml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                default -> {
                    if (c == 0x9 || c == 0xA || c == 0xD || c >= 0x20 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD
                            || c >= 0x10000 && c <= 0x10FFFF) {
                        escaped.appendCodePoint(c);
                    }
                }
            }
        });
        return escaped.toString();
    }

    private static final String CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.styles+xml\"/>"
            + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.numbering+xml\"/>"
            + "</Types>";

    private static final String PACKAGE_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"" + R + "/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = styles();

    private static String styles() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:styles xmlns:w=\"" + W + "\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" "
                + "w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:lang w:val=\"de-DE\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr>"
                + "</w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
                + "</w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:sz w:val=\"48\"/>"
                + "</w:rPr></w:style>");
        int[] sizes = {36, 32, 28, 26, 24, 22};
        for (int level = 1; level <= 6; level++) {
            xml.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level)
                    .append("\"><w:name w:val=\"heading ").append(level).append("\"/><w:basedOn w:val=\"Normal\"/>")
                    .append("<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"")
                    .append(level <= 2 ? 360 : 240).append("\" w:after=\"120\"/><w:outlineLvl w:val=\"")
                    .append(level - 1).append("\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"").append(sizes[level - 1])
                    .append("\"/></w:rPr></w:style>");
        }
        xml.append("<w:style w:type=\"paragraph\" w:styleId=\"PageFacts\"><w:name w:val=\"Page Facts\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:rPr><w:i/><w:color w:val=\"666666\"/><w:sz w:val=\"18\"/></w:rPr>"
                + "</w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>"
                + "<w:spacing w:after=\"120\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr>" + MONO
                + "<w:sz w:val=\"18\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" "
                + "w:color=\"4472C4\"/></w:pBdr><w:ind w:left=\"360\"/></w:pPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"ExportNote\"><w:name w:val=\"Export Note\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:rPr><w:i/><w:color w:val=\"C55A11\"/></w:rPr></w:style>"
                + "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>"
                + "<w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>"
                + "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr>"
                + "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/></w:tblBorders>"
                + "<w:tblCellMar><w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/>"
                + "</w:tblCellMar></w:tblPr></w:style>"
                + "</w:styles>");
        return xml.toString();
    }
}
