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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
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
 * <p>Makros, die der Parser nicht selbst abbildet ({@code include}, {@code excerpt-include}, {@code children},
 * {@code toc}, {@code contentbylabel}, Jira-Abfragen, unbekannte Makros …), zeigen ihren Inhalt nur in der gerenderten
 * Lese-Ansicht der Quelle. Mit {@link #parse(String, String, String)} nimmt der Parser ihn von dort: das n-te Makro
 * eines Namens im Storage ist das n-te Element mit diesem {@code data-macro-name} in der Ansicht (je Seite nur die
 * äußersten solcher Makros). Das gilt nur, wenn Storage und Ansicht für den Namen gleich viele äußerste Makros haben;
 * sonst nimmt der Parser für diesen Namen nichts aus der Ansicht (anders als der Enterprise AI Client, der die Ansicht
 * ungeprüft schreibt). Ohne Inhalt aus der Ansicht bleibt der Hinweis, und das Makro steht in
 * {@link Parsed#omitted()}; ohne Ansicht nur {@code include}, {@code excerpt-include} und {@code children}.
 *
 * <p>Verschachtelung ist auf {@link #MAX_NESTING} Ebenen begrenzt (#90): Tiefer liegender Inhalt wird als reiner Text
 * übernommen, mit einem Hinweis. So bleiben Parser und Converter bei bösartig tiefem Storage ohne
 * {@code StackOverflowError}; jsoup selbst parst und liest Text ohne Rekursion.
 */
public final class StorageParser {

    /** Größter {@code colspan}, den HTML erlaubt; größere Werte zählen als dieser. */
    private static final int MAX_COLSPAN = 1000;

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
    /**
     * Makros, deren Inhalt Confluence beim Rendern aus anderen Seiten berechnet; ohne Ansicht steht nur ihr Fehlen in
     * {@link Parsed#omitted()}.
     */
    static final Set<String> RENDERED_MACROS = Set.of("include", "excerpt-include", "children");
    private static final Set<String> INLINE_MACROS = Set.of("status", "anchor", "jira", "emoticon");

    /** Höchste Verschachtelung von Blöcken und Inline-Elementen, die als Struktur übernommen wird. */
    static final int MAX_NESTING = 64;

    /** Aktuelle Verschachtelung; der Parser wird je Export von einem Thread benutzt. */
    private int depth;

    /** Findet zu Space und Titel einer verlinkten Seite das Ziel im Export, sonst {@code null}. */
    private final BiFunction<String, String, String> pageTargets;

    /** Gerenderter Inhalt aus der Lese-Ansicht je Makro des Storage (Identität des Elements). */
    private Map<Element, Element> rendered = Map.of();

    /** Anzeigenamen erwähnter Benutzer aus der Lese-Ansicht, je {@code ac:link} des Storage. */
    private Map<Element, String> mentions = Map.of();
    /** Makros des Storage, deren Name in Storage und Ansicht unterschiedlich oft vorkommt (Identität). */
    private Set<Element> mismatched = Set.of();

    /** Ob für die Seite eine Lese-Ansicht vorliegt. */
    private boolean withView;

    /** Ausgeführte Makros ohne Inhalt aus der Lese-Ansicht. */
    private final List<Omission> omitted = new ArrayList<>();

    /**
     * Blöcke einer Seite und die ausgeführten Makros, deren Inhalt fehlt.
     *
     * @param omitted die Makros ohne Inhalt aus der Lese-Ansicht, in Dokumentreihenfolge
     */
    public record Parsed(List<Block> blocks, List<Omission> omitted) {
    }

    /**
     * Ein ausgeführtes Makro ohne Inhalt aus der Lese-Ansicht.
     *
     * @param reference     Titel der eingebundenen Seite ({@code include}, {@code excerpt-include}), sonst der
     *                      Makroname
     * @param countMismatch die Ansicht hat für diesen Makronamen eine andere Zahl äußerster Makros als der Storage;
     *                      sonst markiert sie das Makro nicht, oder es liegt keine Ansicht vor
     */
    public record Omission(String reference, boolean countMismatch) {
    }

    /**
     * @param pageTargets Space-Key (oder {@code null} für denselben Space) und Titel → Linkziel wie
     *                    {@code #page-123}, oder {@code null}, wenn die Seite nicht im Export liegt
     */
    public StorageParser(BiFunction<String, String, String> pageTargets) {
        this.pageTargets = pageTargets;
    }

    public List<Block> parse(String storage) {
        return parse(storage, null, null).blocks();
    }

    /**
     * Wie {@link #parse(String)}, die ausgeführten Makros mit ihrem Inhalt aus der gerenderten Lese-Ansicht.
     *
     * @param view    HTML der Lese-Ansicht oder {@code null}
     * @param baseUri Basis für relative Bildadressen der Ansicht
     */
    public Parsed parse(String storage, String view, String baseUri) {
        Element root = Jsoup.parse("<body>" + storage + "</body>", "", Parser.xmlParser()).selectFirst("body");
        depth = 0;
        omitted.clear();
        withView = view != null && !view.isBlank();
        if (withView) {
            pair(outermost(root), rendered(view, baseUri == null ? "" : baseUri));
        }
        mentions = withView ? mentions(root, view) : Map.of();
        try {
            return new Parsed(blocks(root), List.copyOf(omitted));
        } finally {
            rendered = Map.of();
            mentions = Map.of();
            mismatched = Set.of();
        }
    }

    /**
     * Ordnet je Name das n-te äußerste Makro des Storage dem n-ten der Ansicht zu, aber nur bei gleicher Anzahl;
     * sonst gelten alle Makros dieses Namens als abweichend (markiert die Ansicht keines, als nicht gerendert).
     */
    private void pair(Map<String, List<Element>> storage, Map<String, List<Element>> view) {
        rendered = new IdentityHashMap<>();
        mismatched = Collections.newSetFromMap(new IdentityHashMap<>());
        storage.forEach((name, macros) -> {
            List<Element> views = view.getOrDefault(name, List.of());
            if (views.size() == macros.size()) {
                for (int i = 0; i < macros.size(); i++) {
                    rendered.put(macros.get(i), views.get(i));
                }
            } else if (!views.isEmpty()) {
                mismatched.addAll(macros);
            }
        });
    }

    /** Äußerste Makros des Storage je Name, die ihren Inhalt aus der Ansicht nehmen können, in Dokumentreihenfolge. */
    private static Map<String, List<Element>> outermost(Element root) {
        Map<String, List<Element>> macros = new LinkedHashMap<>();
        for (Element macro : root.getAllElements()) {
            if (fromViewMacro(macro) && !nested(macro, StorageParser::fromViewMacro)) {
                macros.computeIfAbsent(macroName(macro), n -> new ArrayList<>()).add(macro);
            }
        }
        return macros;
    }

    private static boolean fromViewMacro(Element element) {
        String name = element.normalName();
        return (name.equals("ac:structured-macro") || name.equals("ac:macro")) && fromView(macroName(element));
    }

    /** Kennung einer Erwähnung ohne Anzeigenamen: Benutzername, sonst Benutzerschlüssel, sonst Account-ID. */
    private static String userId(Element user) {
        for (String attribute : List.of("ri:username", "ri:userkey", "ri:account-id")) {
            if (!user.attr(attribute).isEmpty()) {
                return user.attr(attribute);
            }
        }
        return "";
    }

    /**
     * Anzeigenamen der Benutzererwähnungen wie im Enterprise AI Client, der den Linktext der Lese-Ansicht übernimmt.
     * Zuordnung über {@code ri:username} bzw. {@code ri:account-id} zu {@code data-username} bzw.
     * {@code data-account-id}; sonst (etwa nur {@code ri:userkey}) über die Reihenfolge, aber nur wenn Storage und Ansicht
     * gleich viele Erwähnungen haben. Erwähnungen in Makros, die ihren Inhalt aus der Ansicht nehmen, und Unsichtbares zählen nicht mit.
     */
    private static Map<Element, String> mentions(Element storage, String view) {
        List<Element> shown = new ArrayList<>();
        Map<String, String> byName = new HashMap<>();
        Element body = Jsoup.parse(view).body();
        body.select("script, style, noscript, .hidden, [hidden], [style~=(?i)display\\s*:\\s*none]").remove();
        for (Element mention : body.select("a.user-mention")) {
            boolean included = false;
            for (Element parent = mention.parent(); parent != null && !included; parent = parent.parent()) {
                included = fromView(parent.attr("data-macro-name").toLowerCase(Locale.ROOT));
            }
            String name = collapse(mention.text()).trim();
            if (included || name.isEmpty()) {
                continue;
            }
            shown.add(mention);
            for (String attribute : List.of("data-username", "data-account-id")) {
                if (!mention.attr(attribute).isEmpty()) {
                    byName.put(attribute + "=" + mention.attr(attribute), name);
                }
            }
        }
        List<Element> links = new ArrayList<>();
        for (Element link : storage.select("ac|link")) {
            if (link.selectFirst("ri|user") != null && !nested(link, StorageParser::fromViewMacro)) {
                links.add(link);
            }
        }
        Map<Element, String> names = new IdentityHashMap<>();
        for (int i = 0; i < links.size(); i++) {
            Element user = links.get(i).selectFirst("ri|user");
            String name = byName.get("data-username=" + user.attr("ri:username"));
            if (name == null || user.attr("ri:username").isEmpty()) {
                name = user.attr("ri:account-id").isEmpty() ? null
                        : byName.get("data-account-id=" + user.attr("ri:account-id"));
            }
            if (name == null && links.size() == shown.size()) {
                name = collapse(shown.get(i).text()).trim();
            }
            if (name != null) {
                names.put(links.get(i), name);
            }
        }
        return names;
    }

    /** Äußerste gerenderte Makros der Ansicht je Name; Unsichtbares (Diagrammdaten, Skripte) ist entfernt. */
    private static Map<String, List<Element>> rendered(String view, String baseUri) {
        Element body = Jsoup.parse(view, baseUri).body();
        body.select("script, style, noscript, [style~=(?i)display\\s*:\\s*none], [hidden]").remove();
        Map<String, List<Element>> rendered = new HashMap<>();
        for (Element macro : body.select("[data-macro-name]")) {
            String name = macro.attr("data-macro-name").toLowerCase(Locale.ROOT);
            if (fromView(name) && !nested(macro,
                    parent -> fromView(parent.attr("data-macro-name").toLowerCase(Locale.ROOT)))) {
                rendered.computeIfAbsent(name, n -> new ArrayList<>()).add(macro);
            }
        }
        return rendered;
    }

    private static boolean nested(Element element, Predicate<Element> macro) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (macro.test(parent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ob ein Makro seinen Inhalt aus der Ansicht nehmen kann: alles, was der Parser nicht selbst abbildet. Jira zählt
     * immer mit, damit die Reihenfolge stimmt; ein einzelner Vorgang ({@code key}) bleibt trotzdem Text.
     */
    private static boolean fromView(String name) {
        return !name.isEmpty() && !name.equals("code") && !name.equals("noformat") && !TEXT_DIAGRAMS.contains(name)
                && !ATTACHMENT_DIAGRAMS.contains(name) && !PANELS.containsKey(name)
                && !TRANSPARENT_MACROS.contains(name) && !name.equals("status") && !name.equals("anchor")
                && !name.equals("emoticon");
    }

    /** Jira-Abfrage (ohne {@code key}), für die die Ansicht eine Tabelle liefert: sie wird ein Block. */
    private boolean jiraTable(Element macro) {
        Element view = rendered.get(macro);
        return view != null && macroName(macro).equals("jira") && parameter(macro, "key", "").isBlank()
                && view.selectFirst("table") != null;
    }

    /** Merkt ein Makro ohne Inhalt aus der Ansicht; ohne Ansicht nur die aus anderen Seiten berechneten. */
    private void omit(Element macro, String reference) {
        if (withView || RENDERED_MACROS.contains(macroName(macro))) {
            omitted.add(new Omission(reference, mismatched.contains(macro)));
        }
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
            case "ac:structured-macro", "ac:macro" -> !INLINE_MACROS.contains(macroName(element))
                    || jiraTable(element);
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

    /**
     * Tabelle auf ihrem Raster wie {@code MarkdownTable} des Enterprise AI Client: eine Zelle mit {@code colspan} oder
     * {@code rowspan} wiederholt ihren Inhalt an jeder Stelle, die sie überdeckt, damit kein Wert in die Nachbarspalte
     * rutscht; {@code rowspan="0"} reicht bis zur letzten Zeile, freie Stellen bleiben leer.
     */
    private void table(Element element, List<Block> blocks) {
        List<Element> rows = new ArrayList<>();
        for (Element row : element.select("tr")) {
            if (row.closest("table") == element) {
                rows.add(row);
            }
        }
        Map<Long, List<Block>> taken = new HashMap<>();
        boolean headerRow = false;
        int width = 0;
        for (int r = 0; r < rows.size(); r++) {
            int column = 0;
            boolean allHeaders = true;
            boolean anyCell = false;
            for (Element cell : rows.get(r).children()) {
                if (!cell.normalName().equals("td") && !cell.normalName().equals("th")) {
                    continue;
                }
                while (taken.containsKey(gridKey(r, column))) {
                    column++;
                }
                int colspan = Math.min(MAX_COLSPAN, Math.max(1, span(cell, "colspan")));
                int rowspan = span(cell, "rowspan");
                int lastRow = rowspan == 0 ? rows.size() : Math.min(rows.size(), r + Math.max(1, rowspan));
                List<Block> content = blocks(cell);
                for (int covered = r; covered < lastRow; covered++) {
                    for (int c = column; c < column + colspan; c++) {
                        taken.put(gridKey(covered, c), content);
                    }
                }
                column += colspan;
                anyCell = true;
                allHeaders &= cell.normalName().equals("th");
            }
            if (r == 0) {
                headerRow = allHeaders && anyCell;
            }
            width = Math.max(width, column);
        }
        for (Long key : taken.keySet()) {
            width = Math.max(width, (int) (key & 0xffffffffL) + 1);
        }
        List<List<List<Block>>> grid = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<List<Block>> cells = new ArrayList<>();
            for (int c = 0; c < width; c++) {
                cells.add(taken.getOrDefault(gridKey(r, c), List.of()));
            }
            grid.add(cells);
        }
        if (!grid.isEmpty()) {
            blocks.add(new Table(grid, headerRow));
        }
    }

    /** {@code colspan}/{@code rowspan} der Zelle; fehlend oder unlesbar 1, negativ 1. */
    private static int span(Element cell, String attribute) {
        try {
            int value = Integer.parseInt(cell.attr(attribute).trim());
            return value < 0 ? 1 : value;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static long gridKey(int row, int column) {
        return ((long) row << 32) | (column & 0xffffffffL);
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
        } else if (rendered.containsKey(macro)) {
            // Gerenderter Inhalt aus der Lese-Ansicht; ihre Bilder über ihre Adresse wie bei include
            blocks.addAll(blocks(rendered.get(macro)));
        } else if (name.equals("children")) {
            omit(macro, name);
            blocks.add(new Note("Liste der Unterseiten wird in Confluence berechnet und ist nicht übernommen"));
        } else if (RENDERED_MACROS.contains(name)) {
            Element page = macro.selectFirst("ri|page");
            String title = page == null || page.attr("ri:content-title").isBlank() ? "unbekannt"
                    : page.attr("ri:content-title");
            omit(macro, page == null || page.attr("ri:content-title").isBlank() ? name : title);
            blocks.add(new Note("Eingebundener Inhalt der Seite „" + title + "“ wird dort exportiert"));
        } else if (richBody != null) {
            omit(macro, name);
            blocks.add(new Note("Makro „" + name + "“ nicht unterstützt; sein Inhalt folgt"));
            blocks.addAll(blocks(richBody));
        } else if (plainBody != null) {
            omit(macro, name);
            blocks.add(new Note("Makro „" + name + "“ nicht unterstützt; sein Text folgt"));
            blocks.add(new CodeBlock("", plainText(plainBody)));
        } else {
            omit(macro, name);
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
            case "img" -> {
                // Nur in der Lese-Ansicht: die Adresse wie im Datenbestand (absolut zur Basis der Ansicht)
                String src = element.absUrl("src").isEmpty() ? element.attr("src") : element.absUrl("src");
                if (!src.isBlank()) {
                    String alt = element.attr("alt").isBlank() ? "Bild" : element.attr("alt");
                    out.add(new ImageRef(alt, src));
                }
            }
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
        String name = macroName(macro);
        boolean issue = name.equals("jira") && !parameter(macro, "key", "").isBlank();
        switch (name) {
            case "status" -> out.add(new Text("[" + parameter(macro, "title", "") + "]", true, false, false));
            case "emoticon" -> out.add(Text.plain(":" + parameter(macro, "name", "") + ":"));
            case "anchor" -> {
                // Sprungmarke ohne sichtbaren Inhalt.
            }
            default -> {
                if (!issue && rendered.containsKey(macro)) {
                    // Im Fließtext (Überschrift, Jira-Abfrage ohne Tabelle) bleibt der gerenderte Inhalt Text
                    children(rendered.get(macro), out, false, false, false);
                } else if (name.equals("jira")) {
                    if (!issue) {
                        omit(macro, name);
                    }
                    out.add(Text.plain("Jira " + parameter(macro, "key", "")));
                } else {
                    omit(macro, name);
                    out.add(Text.plain("[Makro " + name + "]"));
                }
            }
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
            String name = mentions.get(link);
            label = name != null ? name : "@" + userId(user);
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
