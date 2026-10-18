package com.aresstack.spaceharvester.parity;

import com.aresstack.spaceharvester.parity.DocumentOutline.Heading;
import com.aresstack.spaceharvester.parity.DocumentOutline.Table;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Liest ein DOCX in das neutrale Modell, ohne Word-Bibliothek: nur das OOXML im ZIP. */
public final class DocxReader {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|berschrift|überschrift)\\s*(\\d)$");

    private DocxReader() {
    }

    public static DocumentOutline read(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Document document = xml(zip, "word/document.xml");
            if (document == null) {
                throw new IOException("Kein word/document.xml in " + file);
            }
            Map<String, Integer> headingStyles = headingStyles(xml(zip, "word/styles.xml"));
            Document settings = xml(zip, "word/settings.xml");
            Document relations = xml(zip, "word/_rels/document.xml.rels");

            List<Heading> headings = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Element paragraph : document.getElementsByTag("w:p")) {
                String content = paragraphText(paragraph);
                int level = level(paragraph, headingStyles);
                if (level > 0 && !content.isBlank()) {
                    headings.add(new Heading(level, content.strip()));
                }
                if (!content.isBlank() && !chrome(paragraph)) {
                    text.append(content).append('\n');
                }
            }

            boolean toc = false;
            boolean pageNumbers = false;
            for (String instruction : instructions(document)) {
                toc |= instruction.strip().toUpperCase(Locale.ROOT).startsWith("TOC");
                pageNumbers |= instruction.strip().toUpperCase(Locale.ROOT).startsWith("PAGE");
            }
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.getName().matches("word/(footer|header)\\d*\\.xml")) {
                    for (String instruction : instructions(xml(zip, entry.getName()))) {
                        pageNumbers |= instruction.strip().toUpperCase(Locale.ROOT).startsWith("PAGE");
                    }
                }
            }
            boolean updateFields = false;
            if (settings != null) {
                for (Element element : settings.getElementsByTag("w:updateFields")) {
                    String value = element.attr("w:val");
                    updateFields |= value.isEmpty() || value.equals("true") || value.equals("on") || value.equals("1");
                }
            }

            List<Table> tables = new ArrayList<>();
            for (Element table : document.getElementsByTag("w:tbl")) {
                List<Element> rows = table.children().stream().filter(child -> child.tagName().equals("w:tr")).toList();
                int columns = 0;
                for (Element row : rows) {
                    columns = Math.max(columns, (int) row.children().stream()
                            .filter(child -> child.tagName().equals("w:tc")).count());
                }
                boolean header = !rows.isEmpty() && !rows.get(0).getElementsByTag("w:tblHeader").isEmpty();
                tables.add(new Table(rows.size(), columns, header));
            }

            Set<String> external = new LinkedHashSet<>();
            if (relations != null) {
                Map<String, String> targets = new HashMap<>();
                for (Element relation : relations.getElementsByTag("Relationship")) {
                    if (relation.attr("Type").endsWith("/hyperlink")) {
                        targets.put(relation.attr("Id"), relation.attr("Target"));
                    }
                }
                for (Element link : document.getElementsByTag("w:hyperlink")) {
                    String target = targets.get(link.attr("r:id"));
                    if (target != null) {
                        external.add(target);
                    }
                }
            }
            int internal = 0;
            for (Element link : document.getElementsByTag("w:hyperlink")) {
                if (!link.attr("w:anchor").isEmpty()) {
                    internal++;
                }
            }
            String plain = text.toString();
            int images = document.getElementsByTag("w:drawing").size() + document.getElementsByTag("v:imagedata").size();
            return new DocumentOutline("docx", headings, toc, updateFields, pageNumbers, 0, images,
                    Patterns.count(Patterns.IMAGE_NOTE, plain), tables, external, internal,
                    Patterns.count(Patterns.MERMAID, plain), plain);
        }
    }

    /** Titel und Platzhalter des Inhaltsverzeichnisses gehören zum Exporter, nicht zum Inhalt. */
    private static boolean chrome(Element paragraph) {
        if (!paragraph.getElementsByTag("w:fldSimple").isEmpty() || paragraph.parents().stream().anyMatch(parent -> parent.tagName().equals("w:sdt"))) {
            return true;
        }
        Element style = paragraph.getElementsByTag("w:pStyle").first();
        return style != null && style.attr("w:val").matches("(?i)title|toc.*|inhaltsverzeichnis.*");
    }

    private static Document xml(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "", Parser.xmlParser());
        }
    }

    /** Stil-ID → Ebene: aus {@code w:outlineLvl} des Stils oder aus dem Namen („heading 2“, „Überschrift 2“). */
    private static Map<String, Integer> headingStyles(Document styles) {
        Map<String, Integer> levels = new HashMap<>();
        if (styles == null) {
            return levels;
        }
        for (Element style : styles.getElementsByTag("w:style")) {
            String id = style.attr("w:styleId");
            Element outline = style.getElementsByTag("w:outlineLvl").first();
            if (outline != null) {
                levels.put(id, Integer.parseInt(outline.attr("w:val")) + 1);
                continue;
            }
            Element name = style.getElementsByTag("w:name").first();
            for (String candidate : new String[]{name == null ? "" : name.attr("w:val"), id}) {
                Matcher matcher = HEADING_STYLE.matcher(candidate.strip());
                if (matcher.matches()) {
                    levels.put(id, Integer.parseInt(matcher.group(1)));
                    break;
                }
            }
        }
        return levels;
    }

    private static int level(Element paragraph, Map<String, Integer> styles) {
        Element properties = paragraph.children().stream().filter(child -> child.tagName().equals("w:pPr"))
                .findFirst().orElse(null);
        if (properties == null) {
            return 0;
        }
        Element outline = properties.getElementsByTag("w:outlineLvl").first();
        if (outline != null) {
            return Integer.parseInt(outline.attr("w:val")) + 1;
        }
        Element style = properties.getElementsByTag("w:pStyle").first();
        if (style == null) {
            return 0;
        }
        String id = style.attr("w:val");
        Integer level = styles.get(id);
        if (level != null) {
            return level;
        }
        Matcher matcher = HEADING_STYLE.matcher(id);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String paragraphText(Element paragraph) {
        StringBuilder text = new StringBuilder();
        for (Element element : paragraph.getAllElements()) {
            switch (element.tagName()) {
                case "w:t" -> text.append(element.wholeText());
                case "w:tab" -> text.append('\t');
                case "w:br" -> text.append('\n');
                default -> {
                }
            }
        }
        return text.toString();
    }

    private static List<String> instructions(Document document) {
        List<String> instructions = new ArrayList<>();
        if (document == null) {
            return instructions;
        }
        for (Element simple : document.getElementsByTag("w:fldSimple")) {
            instructions.add(simple.attr("w:instr"));
        }
        for (Element code : document.getElementsByTag("w:instrText")) {
            instructions.add(code.wholeText());
        }
        return instructions;
    }
}
