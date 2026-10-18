package com.aresstack.spaceharvester.output.convert;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jsoup.Jsoup;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/**
 * Liest das Confluence-Storage-Format (XHTML mit {@code ac:}- und {@code ri:}-Elementen) in ein
 * {@link StorageDocument}. Die Payload bleibt unverändert; der Parser erzeugt nur ein neues Abbild.
 *
 * <p>Diagramme: Makros mit Textquelle im Storage (PlantUML, Mermaid) werden als Codeblock mit dieser Quelle
 * übernommen. draw.io und Gliffy halten ihre Quelle in einem Seitenanhang, der nicht zur Payload gehört: draw.io wird ein
 * {@link ImageRef} {@code drawio:[<Seiten-ID>/]<Name>}, das die Konverter aus dem Datenbestand zeichnen; für Gliffy
 * steht ein {@link Note} mit dem Diagrammnamen. Bilder bleiben {@link ImageRef}. Was sich nicht abbilden lässt, wird als
 * {@link Note} sichtbar, nie still verworfen.
 *
 * <p>Verschachtelung ist auf {@link #MAX_NESTING} Ebenen begrenzt (#90): Tiefer liegender Inhalt wird als reiner Text
 * übernommen, mit einem Hinweis. So bleiben Parser und Converter bei bösartig tiefem Storage ohne
 * {@code StackOverflowError}; jsoup selbst parst und liest Text ohne Rekursion.
 */
public final class StorageParser {

    private static final Map<String, String> PANELS = Map.of("info", "Info", "note", "Hinweis", "warning", "Warnung",
            "tip", "Tipp", "panel", "Kasten", "expand", "Aufklappbar");
    private static final Set<String> TEXT_DIAGRAMS = Set.of("plantuml", "plantumlrender", "mermaid", "mermaid-cloud",
            "mermaid-macro");
    private static final Set<String> DRAWIO = Set.of("drawio", "drawio-sketch", "inc-drawio");
    private static final Set<String> ATTACHMENT_DIAGRAMS = Set.of("drawio", "drawio-sketch", "inc-drawio", "gliffy");
    private static final Set<String> CONTAINERS = Set.of("div", "section", "article", "body", "ac:layout",
            "ac:layout-section", "ac:layout-cell", "ac:rich-text-body", "center");
    private static final Set<String> TRANSPARENT_MACROS = Set.of("details", "section", "column", "div", "excerpt",
            "ui-tabs", "ui-tab", "ui-expand", "center");
    private static final Set<String> INLINE_MACROS = Set.of("status", "anchor", "jira", "emoticon");

    /** Höchste Verschachtelung von Blöcken und Inline-Elementen, die als Struktur übernommen wird. */
    static final int MAX_NESTING = 64;

    /** Aktuelle Verschachtelung; der Parser wird je Export von einem Thread benutzt. */
    private int depth;

    /** Findet zu Space und Titel einer verlinkten Seite das Ziel im Export, sonst {@code null}. */
    private final BiFunction<String, String, String> pageTargets;

    /**
     * @param pageTargets Space-Key (oder {@code null} für denselben Space) und Titel → Linkziel wie
     *                    {@code #page-123}, oder {@code null}, wenn die Seite nicht im Export liegt
     */
    public StorageParser(BiFunction<String, String, String> pageTargets) {
        this.pageTargets = pageTargets;
    }

    public List<Block> parse(String storage) {
        Element root = Jsoup.parse("<body>" + storage + "</body>", "", Parser.xmlParser()).selectFirst("body");
        depth = 0;
        return blocks(root);
    }

    /** Blöcke eines Containers; lose Inline-Inhalte werden zu Absätzen zusammengefasst. */
    private List<Block> blocks(Element container) {
        List<Block> blocks = new ArrayList<>();
        if (container == null) {
            return blocks;
        }
        if (depth >= MAX_NESTING) {
            blocks.add(new Note("Verschachtelung tiefer als " + MAX_NESTING + " Ebenen; der Inhalt folgt als Text"));
            String text = collapse(container.text()).trim();
            if (!text.isEmpty()) {
                blocks.add(new Paragraph(List.of(Text.plain(text))));
            }
            return blocks;
        }
        depth++;
        try {
            return blocksOf(container, blocks);
        } finally {
            depth--;
        }
    }

    private List<Block> blocksOf(Element container, List<Block> blocks) {
        List<Inline> pending = new ArrayList<>();
        for (Node node : container.childNodes()) {
            if (node instanceof Element element && isBlock(element)) {
                flush(pending, blocks);
                block(element, blocks);
            } else {
                inline(node, pending, false, false, false);
            }
        }
        flush(pending, blocks);
        return blocks;
    }

    private static void flush(List<Inline> pending, List<Block> blocks) {
        if (!pending.isEmpty() && !blank(pending)) {
            blocks.add(new Paragraph(trim(pending)));
        }
        pending.clear();
    }

    private boolean isBlock(Element element) {
        String name = element.normalName();
        return switch (name) {
            case "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table", "pre", "blockquote", "hr",
                 "ac:task-list" -> true;
            case "ac:structured-macro", "ac:macro" -> !INLINE_MACROS.contains(macroName(element));
            default -> CONTAINERS.contains(name);
        };
    }

    private void block(Element element, List<Block> blocks) {
        String name = element.normalName();
        switch (name) {
            case "p" -> blocks.addAll(blocks(element));
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                List<Inline> content = inlines(element);
                if (!blank(content)) {
                    blocks.add(new Heading(name.charAt(1) - '0', trim(content)));
                }
            }
            case "ul", "ol" -> blocks.add(list(element, name.equals("ol")));
            case "ac:task-list" -> blocks.add(tasks(element));
            case "table" -> table(element, blocks);
            case "pre" -> blocks.add(new CodeBlock("", element.wholeText()));
            case "blockquote" -> blocks.add(new Panel("Zitat", blocks(element)));
            case "hr" -> blocks.add(new Rule());
            case "ac:structured-macro", "ac:macro" -> macro(element, blocks);
            default -> blocks.addAll(blocks(element));
        }
    }

    private ListBlock list(Element element, boolean ordered) {
        List<List<Block>> items = new ArrayList<>();
        for (Element item : element.children()) {
            if (item.normalName().equals("li")) {
                items.add(blocks(item));
            }
        }
        return new ListBlock(ordered, items, null);
    }

    private ListBlock tasks(Element element) {
        List<List<Block>> items = new ArrayList<>();
        List<Boolean> checked = new ArrayList<>();
        for (Element task : element.children()) {
            if (task.normalName().equals("ac:task")) {
                Element status = child(task, "ac:task-status");
                checked.add(status != null && status.text().trim().equals("complete"));
                items.add(blocks(child(task, "ac:task-body")));
            }
        }
        return new ListBlock(false, items, checked);
    }

    private void table(Element element, List<Block> blocks) {
        List<List<List<Block>>> rows = new ArrayList<>();
        boolean headerRow = false;
        for (Element row : element.select("tr")) {
            if (row.closest("table") != element) {
                continue;
            }
            List<List<Block>> cells = new ArrayList<>();
            boolean allHeaders = true;
            for (Element cell : row.children()) {
                if (cell.normalName().equals("td") || cell.normalName().equals("th")) {
                    cells.add(blocks(cell));
                    allHeaders &= cell.normalName().equals("th");
                }
            }
            if (rows.isEmpty()) {
                headerRow = allHeaders && !cells.isEmpty();
            }
            rows.add(cells);
        }
        if (!rows.isEmpty()) {
            blocks.add(new Table(rows, headerRow));
        }
    }

    /**
     * Verweis auf ein draw.io-Diagramm: {@code drawio:<Name>} auf dieser Seite, {@code drawio:<Seiten-ID>/<Name>} für
     * {@code inc-drawio} von einer anderen Seite; {@code null} ohne Diagrammnamen.
     */
    private String drawioReference(Element macro, String name) {
        String diagram = parameter(macro, "diagramName", "");
        if (diagram.isBlank() || diagram.contains("/")) {
            return null;
        }
        String page = parameter(macro, "pageId", "");
        if (name.equals("inc-drawio") && !page.matches("\\d+")) {
            return null;
        }
        return "drawio:" + (page.matches("\\d+") ? page + "/" : "") + diagram;
    }

    private void macro(Element macro, List<Block> blocks) {
        String name = macroName(macro);
        Element richBody = child(macro, "ac:rich-text-body");
        Element plainBody = child(macro, "ac:plain-text-body");
        if (name.equals("code") || name.equals("noformat")) {
            blocks.add(new CodeBlock(parameter(macro, "language", ""), plainText(plainBody)));
        } else if (TEXT_DIAGRAMS.contains(name)) {
            String language = name.startsWith("mermaid") ? "mermaid" : "plantuml";
            blocks.add(new CodeBlock(language, plainText(plainBody)));
        } else if (DRAWIO.contains(name) && drawioReference(macro, name) != null) {
            // Gezeichnet wird aus dem Anhang im Datenbestand (#190); fehlt er, meldet der Writer die Abbildung
            String diagram = parameter(macro, "diagramName", "");
            blocks.add(new Paragraph(List.of(new ImageRef("Diagramm (draw.io) „" + diagram + "“",
                    drawioReference(macro, name)))));
        } else if (ATTACHMENT_DIAGRAMS.contains(name)) {
            String diagram = parameter(macro, "diagramName", parameter(macro, "name", "ohne Namen"));
            blocks.add(new Note("Diagramm (" + (name.equals("gliffy") ? "Gliffy" : "draw.io") + ") „" + diagram
                    + "“: die Quelle ist der Seitenanhang „" + diagram + "“ und nicht Teil der Seiten-Payload"));
        } else if (PANELS.containsKey(name)) {
            String title = parameter(macro, "title", null);
            String label = PANELS.get(name) + (title == null || title.isBlank() ? "" : ": " + title);
            blocks.add(new Panel(label, blocks(richBody)));
        } else if (TRANSPARENT_MACROS.contains(name)) {
            blocks.addAll(blocks(richBody));
        } else if (name.equals("include") || name.equals("excerpt-include")) {
            Element page = macro.selectFirst("ri|page");
            String title = page == null ? "unbekannt" : page.attr("ri:content-title");
            blocks.add(new Note("Eingebundener Inhalt der Seite „" + title + "“ wird dort exportiert"));
        } else if (richBody != null) {
            blocks.add(new Note("Makro „" + name + "“ nicht unterstützt; sein Inhalt folgt"));
            blocks.addAll(blocks(richBody));
        } else if (plainBody != null) {
            blocks.add(new Note("Makro „" + name + "“ nicht unterstützt; sein Text folgt"));
            blocks.add(new CodeBlock("", plainText(plainBody)));
        } else {
            blocks.add(new Note("Makro „" + name + "“ wird in Confluence berechnet und ist nicht übernommen"));
        }
    }

    private List<Inline> inlines(Element element) {
        List<Inline> content = new ArrayList<>();
        for (Node node : element.childNodes()) {
            inline(node, content, false, false, false);
        }
        return content;
    }

    private void inline(Node node, List<Inline> out, boolean bold, boolean italic, boolean code) {
        if (node instanceof CDataNode cdata) {
            out.add(new Text(cdata.text(), bold, italic, code));
            return;
        }
        if (node instanceof TextNode text) {
            String value = collapse(text.getWholeText());
            if (!value.isEmpty()) {
                out.add(new Text(value, bold, italic, code));
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        if (depth >= MAX_NESTING) {
            String value = collapse(element.text());
            if (!value.isEmpty()) {
                out.add(new Text(value, bold, italic, code));
            }
            return;
        }
        depth++;
        try {
            inlineElement(element, out, bold, italic, code);
        } finally {
            depth--;
        }
    }

    private void inlineElement(Element element, List<Inline> out, boolean bold, boolean italic, boolean code) {
        switch (element.normalName()) {
            case "strong", "b" -> children(element, out, true, italic, code);
            case "em", "i", "cite" -> children(element, out, bold, true, code);
            case "code", "tt", "kbd", "samp" -> children(element, out, bold, italic, true);
            case "br" -> out.add(new LineBreak());
            case "a" -> out.add(new Link(collapse(element.text()).trim(), emptyToNull(element.attr("href"))));
            case "ac:link" -> out.add(link(element));
            case "ac:image" -> out.add(image(element));
            case "ac:emoticon" -> out.add(Text.plain(":" + element.attr("ac:name") + ":"));
            case "ac:placeholder", "ac:parameter" -> {
                // Platzhalter des Editors und Makroparameter sind kein Seiteninhalt.
            }
            case "time" -> out.add(new Text(element.attr("datetime"), bold, italic, code));
            case "ac:structured-macro", "ac:macro" -> inlineMacro(element, out);
            default -> children(element, out, bold, italic, code);
        }
    }

    private void children(Element element, List<Inline> out, boolean bold, boolean italic, boolean code) {
        for (Node child : element.childNodes()) {
            inline(child, out, bold, italic, code);
        }
    }

    private void inlineMacro(Element macro, List<Inline> out) {
        switch (macroName(macro)) {
            case "status" -> out.add(new Text("[" + parameter(macro, "title", "") + "]", true, false, false));
            case "jira" -> out.add(Text.plain("Jira " + parameter(macro, "key", "")));
            case "emoticon" -> out.add(Text.plain(":" + parameter(macro, "name", "") + ":"));
            case "anchor" -> {
                // Sprungmarke ohne sichtbaren Inhalt.
            }
            default -> out.add(Text.plain("[Makro " + macroName(macro) + "]"));
        }
    }

    private Link link(Element link) {
        Element body = child(link, "ac:link-body");
        Element plainBody = child(link, "ac:plain-text-link-body");
        String label = body != null ? collapse(body.text()).trim() : plainBody != null ? plainText(plainBody).trim()
                : "";
        Element page = link.selectFirst("ri|page");
        Element attachment = link.selectFirst("ri|attachment");
        Element url = link.selectFirst("ri|url");
        Element user = link.selectFirst("ri|user");
        String target = null;
        if (page != null) {
            String title = page.attr("ri:content-title");
            target = pageTargets.apply(emptyToNull(page.attr("ri:space-key")), title);
            label = label.isEmpty() ? title : label;
        } else if (attachment != null) {
            String file = attachment.attr("ri:filename");
            target = "attachment:" + file;
            label = label.isEmpty() ? file : label;
        } else if (url != null) {
            target = emptyToNull(url.attr("ri:value"));
            label = label.isEmpty() ? url.attr("ri:value") : label;
        } else if (user != null && label.isEmpty()) {
            label = "@" + (user.hasAttr("ri:username") ? user.attr("ri:username") : user.attr("ri:userkey"));
        }
        if (!link.attr("ac:anchor").isEmpty() && target == null && page == null) {
            label = label.isEmpty() ? link.attr("ac:anchor") : label;
        }
        return new Link(label, target);
    }

    private static ImageRef image(Element image) {
        Element attachment = image.selectFirst("ri|attachment");
        Element url = image.selectFirst("ri|url");
        String reference = attachment != null ? "attachment:" + attachment.attr("ri:filename")
                : url != null ? url.attr("ri:value") : "";
        String alt = image.attr("ac:alt");
        if (alt.isEmpty()) {
            alt = image.attr("ac:title");
        }
        if (alt.isEmpty()) {
            alt = attachment != null ? attachment.attr("ri:filename") : "Bild";
        }
        return new ImageRef(alt, reference);
    }

    private static String macroName(Element macro) {
        return macro.attr("ac:name").toLowerCase(Locale.ROOT);
    }

    private static String parameter(Element macro, String name, String fallback) {
        for (Element child : macro.children()) {
            if (child.normalName().equals("ac:parameter") && child.attr("ac:name").equals(name)) {
                return child.text();
            }
        }
        return fallback;
    }

    private static Element child(Element element, String name) {
        for (Element child : element.children()) {
            if (child.normalName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    private static String plainText(Element body) {
        if (body == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Node node : body.childNodes()) {
            if (node instanceof CDataNode cdata) {
                text.append(cdata.text());
            } else if (node instanceof TextNode plain) {
                text.append(plain.getWholeText());
            }
        }
        return text.toString();
    }

    private static String collapse(String text) {
        return text.replace(' ', ' ').replaceAll("\\s+", " ");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean blank(List<Inline> content) {
        return content.stream().allMatch(inline -> inline instanceof Text text && text.text().isBlank()
                || inline instanceof LineBreak);
    }

    /** Ohne führende und abschließende Leerzeichen des Absatzes. */
    private static List<Inline> trim(List<Inline> content) {
        List<Inline> trimmed = new ArrayList<>(content);
        while (!trimmed.isEmpty() && (trimmed.getFirst() instanceof LineBreak
                || trimmed.getFirst() instanceof Text t && t.text().isBlank())) {
            trimmed.removeFirst();
        }
        while (!trimmed.isEmpty() && (trimmed.getLast() instanceof LineBreak
                || trimmed.getLast() instanceof Text t && t.text().isBlank())) {
            trimmed.removeLast();
        }
        if (!trimmed.isEmpty() && trimmed.getFirst() instanceof Text first) {
            trimmed.set(0, new Text(first.text().stripLeading(), first.bold(), first.italic(), first.code()));
        }
        if (!trimmed.isEmpty() && trimmed.getLast() instanceof Text last) {
            trimmed.set(trimmed.size() - 1, new Text(last.text().stripTrailing(), last.bold(), last.italic(),
                    last.code()));
        }
        return trimmed;
    }
}
