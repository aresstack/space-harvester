package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeStructuralBlock;
import com.aresstack.enterpriseai.source.api.text.MarkdownTable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Überführt das von {@code action=parse} gelieferte HTML in indexierbaren Klartext (paketintern).
 *
 * <p>Die Bereinigung (Inhaltsverzeichnis, Bearbeiten-Links, Skripte, Styles, unsichtbare Elemente, Bilder
 * samt Rahmen) ist aus MainframeMate {@code HtmlPostProcessor} übernommen. Statt des dortigen
 * Regex-Tag-Strippings ({@code WikiSourceScanner.stripHtml}) läuft die Textgewinnung über den jsoup-Baum und
 * erhält Struktur für das Chunking: Überschriften als Markdown-Zeilen ({@code ## Titel}), Absätze durch
 * Leerzeilen getrennt, Listenpunkte mit {@code - }, Tabellenzeilen mit {@code |} zwischen den Zellen.
 */
final class WikiHtmlText {

    private WikiHtmlText() {
    }

    static String toText(String html) {
        Document doc = Jsoup.parseBodyFragment(html == null ? "" : html);
        clean(doc);
        TextBuilder out = new TextBuilder();
        renderChildren(doc.body(), out);
        return out.result();
    }

    private static void clean(Document doc) {
        doc.select("#toc, .toc, .mw-toc, .mw-editsection, script, style, noscript").remove();
        doc.select(".mw-empty-elt, .noprint, .mw-indicators, .mw-jump-link, .catlinks").remove();
        doc.select(".thumb, .thumbinner, figure, .mw-file-element, img").remove();
        doc.select("sup.reference").remove();
        doc.select("[style~=(?i)display:\\s*none]").remove();
    }


    /**
     * Die Strukturblöcke der Seite aus dem Dokumentbaum (askai {@code SourceCapture.StructuralBlock}): Titel und
     * Überschriften, Absätze, Listenpunkte, Tabellenzeilen, Zitate und Code, jeweils mit ihrem Überschriftenpfad.
     * Bereinigt wird wie für {@link #toText}.
     */
    static List<KnowledgeStructuralBlock> toBlocks(String title, String html) {
        Document doc = Jsoup.parseBodyFragment(html == null ? "" : html);
        clean(doc);
        Structure structure = new Structure();
        if (title != null && !title.trim().isEmpty()) {
            structure.heading(1, title.trim());
        }
        structure.walk(doc.body());
        structure.flush();
        return structure.blocks;
    }

    /** Sammelt die Blöcke in Lesereihenfolge; Überschriften setzen den Pfad der folgenden Blöcke. */
    private static final class Structure {

        private final List<KnowledgeStructuralBlock> blocks = new ArrayList<KnowledgeStructuralBlock>();
        private final List<String> headings = new ArrayList<String>();
        private final StringBuilder paragraph = new StringBuilder();

        void walk(Element parent) {
            for (Node child : parent.childNodes()) {
                if (child instanceof TextNode) {
                    paragraph.append(((TextNode) child).text());
                } else if (child instanceof Element) {
                    visit((Element) child);
                }
            }
        }

        private void visit(Element el) {
            String tag = el.tagName().toLowerCase(java.util.Locale.ROOT);
            if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
                String text = el.text().trim();
                if (!text.isEmpty()) {
                    heading(tag.charAt(1) - '0', text);
                }
            } else if ("br".equals(tag)) {
                paragraph.append(' ');
            } else if ("li".equals(tag)) {
                flush();
                Element own = el.clone();
                own.select("ul, ol").remove();
                String text = own.text().trim();
                if (!text.isEmpty()) {
                    Element parent = el.parent();
                    String marker = parent != null && "ol".equalsIgnoreCase(parent.tagName())
                            ? (el.elementSiblingIndex() + 1) + ". " : "- ";
                    add(KnowledgeStructuralBlock.Kind.LIST_ITEM, marker + text);
                }
                for (Element nested : el.select("> ul, > ol")) {
                    visit(nested);
                }
            } else if ("table".equals(tag)) {
                flush();
                for (List<String> row : table(el).grid()) {
                    add(KnowledgeStructuralBlock.Kind.TABLE_ROW, MarkdownTable.rowText(row));
                }
            } else if ("pre".equals(tag)) {
                flush();
                String code = el.wholeText().replaceAll("\\s+$", "");
                if (!code.trim().isEmpty()) {
                    add(KnowledgeStructuralBlock.Kind.CODE, code);
                }
            } else if ("blockquote".equals(tag)) {
                flush();
                String text = el.text().trim();
                if (!text.isEmpty()) {
                    add(KnowledgeStructuralBlock.Kind.QUOTE, text);
                }
            } else if (isBlock(tag)) {
                flush();
                walk(el);
                flush();
            } else {
                walk(el);
            }
        }

        void heading(int level, String text) {
            flush();
            while (headings.size() >= level) {
                headings.remove(headings.size() - 1);
            }
            while (headings.size() < level - 1) {
                headings.add("");
            }
            headings.add(text);
            add(KnowledgeStructuralBlock.Kind.HEADING, text);
        }

        void flush() {
            String text = paragraph.toString().replaceAll("[ \\t\\n\\u00A0]+", " ").trim();
            paragraph.setLength(0);
            if (!text.isEmpty()) {
                add(KnowledgeStructuralBlock.Kind.PARAGRAPH, text);
            }
        }

        private void add(KnowledgeStructuralBlock.Kind kind, String text) {
            StringBuilder path = new StringBuilder();
            for (String heading : headings) {
                if (heading.isEmpty()) {
                    continue;
                }
                if (path.length() > 0) {
                    path.append(" > ");
                }
                path.append(heading);
            }
            blocks.add(new KnowledgeStructuralBlock(kind, path.toString(), text));
        }
    }

    private static void renderChildren(Element parent, TextBuilder out) {
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode) {
                out.inline(((TextNode) child).text());
            } else if (child instanceof Element) {
                render((Element) child, out);
            }
        }
    }

    private static void render(Element el, TextBuilder out) {
        String tag = el.tagName().toLowerCase(java.util.Locale.ROOT);
        if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
            String text = el.text().trim();
            if (!text.isEmpty()) {
                out.block();
                out.line(repeat('#', tag.charAt(1) - '0') + " " + text);
                out.block();
            }
        } else if ("br".equals(tag)) {
            out.newline();
        } else if ("li".equals(tag)) {
            out.newline();
            out.inline("- ");
            renderChildren(el, out);
            out.newline();
        } else if ("table".equals(tag)) {
            out.block();
            String markdown = table(el).toMarkdown();
            if (!markdown.isEmpty()) {
                out.raw(markdown);
            }
            out.block();
        } else if ("pre".equals(tag)) {
            out.block();
            out.raw(el.wholeText());
            out.block();
        } else if (isBlock(tag)) {
            out.block();
            renderChildren(el, out);
            out.block();
        } else {
            renderChildren(el, out);
        }
    }

    private static boolean isBlock(String tag) {
        return "p".equals(tag) || "div".equals(tag) || "section".equals(tag) || "blockquote".equals(tag)
                || "ul".equals(tag) || "ol".equals(tag) || "dl".equals(tag) || "dt".equals(tag)
                || "dd".equals(tag) || "center".equals(tag) || "figcaption".equals(tag) || "hr".equals(tag);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** Sammelt Text zeilenweise; normalisiert Leerraum innerhalb von Zeilen und Leerzeilen zwischen Blöcken. */
    private static final class TextBuilder {

        private final StringBuilder text = new StringBuilder();
        private final StringBuilder current = new StringBuilder();

        void inline(String value) {
            current.append(value);
        }

        void line(String value) {
            newline();
            current.append(value);
            newline();
        }

        void raw(String value) {
            newline();
            for (String l : value.split("\r?\n", -1)) {
                appendLine(rtrim(l));
            }
        }

        void newline() {
            String normalized = current.toString().replaceAll("[ \\t\\u00A0]+", " ").trim();
            current.setLength(0);
            if (!normalized.isEmpty()) {
                appendLine(normalized);
            }
        }

        void block() {
            newline();
            if (text.length() > 0 && !endsWithBlankLine()) {
                text.append('\n');
            }
        }

        private void appendLine(String value) {
            text.append(value).append('\n');
        }

        private boolean endsWithBlankLine() {
            int n = text.length();
            return n >= 2 && text.charAt(n - 1) == '\n' && text.charAt(n - 2) == '\n';
        }

        String result() {
            newline();
            return text.toString().replaceAll("\n{3,}", "\n\n").trim();
        }

        private static String rtrim(String s) {
            int end = s.length();
            while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
                end--;
            }
            return s.substring(0, end);
        }
    }

    /** Lay out the table's own rows (not those of nested tables) with spans and empty cells kept in place. */
    static MarkdownTable table(Element table) {
        MarkdownTable markdown = new MarkdownTable();
        for (Element row : table.select("> tr, > thead > tr, > tbody > tr, > tfoot > tr")) {
            markdown.row();
            for (Element cell : row.select("> th, > td")) {
                markdown.cell(cell.text(), span(cell, "colspan", 1), span(cell, "rowspan", 1));
            }
        }
        return markdown;
    }

    /** A span attribute; missing or unparseable is {@code def}, the table bounds the value. */
    private static int span(Element cell, String attribute, int def) {
        try {
            return Integer.parseInt(cell.attr(attribute).trim());
        } catch (NumberFormatException missing) {
            return def;
        }
    }
}
