package com.aresstack.spaceharvester.application.assets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Markdown-Ansicht einer strukturierten Extraktion (ADR 0026, Punkt 3, AP6c): Die Blöcke führen, Markdown ist eine
 * daraus gebildete Ansicht wie der reine Text. Schreibt fortlaufend in ein {@link Appendable}, Block für Block, ohne das
 * Dokument im Speicher zu halten.
 *
 * <ul>
 *   <li>Überschrift als ATX ({@code #} bis {@code ######}), Absatz als Absatz, Listenpunkt mit {@code -} oder
 *       {@code 1.}, eingerückt nach Ebene, Code als Zaun mit optionaler Sprache.</li>
 *   <li>Tabelle als GFM-Tabelle; ohne Kopfzellen in der ersten Zeile mit leerer Kopfzeile. Ein Folgestück
 *       ({@link Block.Table#continued()}) setzt die vorige Tabelle ohne neue Kopfzeile fort.</li>
 *   <li>Verweise stehen am Block getrennt; die Ansicht setzt sie an die erste Stelle ihrer Beschriftung im Text. Ohne
 *       Beschriftung im Text folgen sie nach dem Block als eigene Zeile. Nur {@code http}, {@code https},
 *       {@code mailto}, {@code ftp} und relative Ziele werden Verweise, andere bleiben als Text stehen.</li>
 *   <li>Fundstellen als HTML-Kommentar vor dem ersten Block einer neuen Seite, Folie oder eines neuen Blatts.</li>
 *   <li>Text wird maskiert, sodass Quelltext nie als Auszeichnung gelesen wird.</li>
 * </ul>
 *
 * Die Ansicht ändert keinen Inhalt; ändert sich ihre Ausgabe, steigt {@link #SCHEMA}.
 */
public final class MarkdownView {

    /** Version der Ansicht (ADR 0026, Punkt 11: Ansicht). */
    public static final String SCHEMA = "markdown/v1";

    private static final Pattern SPECIAL = Pattern.compile("[\\\\`*_\\[\\]<>|~]");
    private static final Pattern LINE_START = Pattern.compile("^(\\s*)([#+\\-=>]|\\d+[.)])");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):");

    private final Appendable out;
    private Block previous;
    private Optional<Location> location = Optional.empty();
    private int tableColumns;
    private final List<Link> pendingLinks = new ArrayList<>();

    public MarkdownView(Appendable out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    /** Schreibt den nächsten Block in Lesereihenfolge. */
    public void accept(Block block) {
        Objects.requireNonNull(block, "block");
        try {
            boolean continuesTable = block instanceof Block.Table table && table.continued()
                    && previous instanceof Block.Table;
            if (!continuesTable) {
                flushLinks();
            }
            boolean moved = block.location().isPresent() && !block.location().equals(location);
            if (previous != null && !continuesTable) {
                boolean listGoesOn = previous instanceof Block.ListItem && block instanceof Block.ListItem && !moved;
                out.append(listGoesOn ? "\n" : "\n\n");
            }
            if (moved && !continuesTable) {
                out.append("<!-- ").append(describe(block.location().get())).append(" -->\n\n");
            }
            if (block.location().isPresent()) {
                location = block.location();
            }
            switch (block) {
                case Block.Heading heading -> {
                    out.append("#".repeat(heading.level())).append(' ');
                    out.append(inline(heading.text().replaceAll("\\s+", " ").strip(), heading.links()));
                }
                case Block.Paragraph paragraph -> out.append(lines(paragraph.text(), paragraph.links(), ""));
                case Block.ListItem item -> {
                    String indent = (item.ordered() ? "   " : "  ").repeat(item.level() - 1);
                    String marker = item.ordered() ? "1. " : "- ";
                    out.append(indent).append(marker)
                            .append(lines(item.text(), item.links(), indent + " ".repeat(marker.length())));
                }
                case Block.Code code -> code(code);
                case Block.Table table -> table(table, continuesTable);
            }
            previous = block;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Schließt die Ansicht ab: noch offene Verweise einer Tabelle, Zeilenende. */
    public void finish() {
        try {
            flushLinks();
            if (previous != null) {
                out.append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ein ganzes Dokument als Zeichenkette (für Tests und kleine Dokumente). */
    public static String of(Iterable<? extends Block> blocks) {
        StringBuilder text = new StringBuilder();
        MarkdownView view = new MarkdownView(text);
        blocks.forEach(view::accept);
        view.finish();
        return text.toString();
    }

    private void code(Block.Code code) throws IOException {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < code.text().length(); i++) {
            run = code.text().charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        out.append(fence).append(code.language().map(name -> name.replaceAll("[^A-Za-z0-9+#.\\-]", "")).orElse(""))
                .append('\n').append(code.text());
        if (!code.text().endsWith("\n")) {
            out.append('\n');
        }
        out.append(fence);
        // Verweise im Code bleiben Text; ihre Ziele folgen nach dem Block.
        pendingLinks.addAll(code.links());
    }

    private void table(Block.Table table, boolean continued) throws IOException {
        List<Link> unused = new ArrayList<>(table.links());
        List<List<Block.Cell>> rows = new ArrayList<>();
        int columns = continued ? tableColumns : 0;
        long row = Long.MIN_VALUE;
        for (Block.Cell cell : table.cells()) {
            if (cell.row() != row) {
                rows.add(new ArrayList<>());
                row = cell.row();
            }
            rows.getLast().add(cell);
            if (!continued) {
                columns = Math.max(columns, cell.column() + 1);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        columns = Math.max(columns, 1);
        int first = 0;
        if (!continued) {
            boolean header = rows.getFirst().stream().allMatch(Block.Cell::header);
            out.append(row(header ? rows.getFirst() : List.of(), columns, unused)).append('\n');
            out.append("|").append(" --- |".repeat(columns));
            first = header ? 1 : 0;
            tableColumns = columns;
        }
        for (int i = first; i < rows.size(); i++) {
            out.append('\n').append(row(rows.get(i), columns, unused));
        }
        pendingLinks.addAll(unused);
    }

    /** Eine Tabellenzeile; Zellen jenseits der Spaltenzahl der Kopfzeile gehen in die letzte Spalte. */
    private String row(List<Block.Cell> cells, int columns, List<Link> unused) {
        String[] texts = new String[columns];
        java.util.Arrays.fill(texts, "");
        for (Block.Cell cell : cells) {
            int column = Math.min(cell.column(), columns - 1);
            String text = inlineUsing(cell.text().replaceAll("\\s+", " ").strip(), unused);
            texts[column] = texts[column].isEmpty() ? text : texts[column] + " " + text;
        }
        StringBuilder line = new StringBuilder("|");
        for (String text : texts) {
            line.append(' ').append(text).append(" |");
        }
        return line.toString();
    }

    /** Text mit Zeilenumbrüchen als harte Umbrüche; Folgezeilen mit {@code indent}. */
    private String lines(String text, List<Link> links, String indent) {
        List<Link> unused = new ArrayList<>(links);
        String[] parts = text.strip().split("\\R", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append("\\\n").append(indent);
            }
            result.append(inlineUsing(parts[i].strip(), unused));
        }
        pendingLinks.addAll(unused);
        return result.toString();
    }

    private String inline(String text, List<Link> links) {
        List<Link> unused = new ArrayList<>(links);
        String result = inlineUsing(text, unused);
        pendingLinks.addAll(unused);
        return result;
    }

    /** Maskiert Text und setzt Verweise an ihre Beschriftung; gesetzte Verweise fallen aus {@code unused}. */
    private static String inlineUsing(String text, List<Link> unused) {
        StringBuilder result = new StringBuilder();
        int from = 0;
        while (true) {
            int best = -1;
            Link found = null;
            for (Link link : unused) {
                if (link.label().isEmpty() || target(link).isEmpty()) {
                    continue;
                }
                int at = text.indexOf(link.label().get(), from);
                if (at >= 0 && (best < 0 || at < best)) {
                    best = at;
                    found = link;
                }
            }
            if (found == null) {
                break;
            }
            String label = found.label().get();
            result.append(escape(text.substring(from, best), from == 0));
            result.append('[').append(escape(label, false)).append("](").append(target(found).get()).append(')');
            from = best + label.length();
            unused.remove(found);
        }
        result.append(escape(text.substring(from), from == 0));
        return result.toString();
    }

    private void flushLinks() throws IOException {
        if (pendingLinks.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Link link : pendingLinks) {
            Optional<String> target = target(link);
            String label = escape(link.label().orElse(link.target()).replaceAll("\\s+", " ").strip(), false);
            lines.add(target.map(t -> "[" + label + "](" + t + ")").orElse(escape(link.target(), false)));
        }
        pendingLinks.clear();
        out.append("\n\n").append(String.join("\\\n", lines));
    }

    /** Das Ziel als Markdown-Ziel; leer bei nicht zugelassenem Schema oder unbrauchbaren Zeichen. */
    private static Optional<String> target(Link link) {
        String target = link.target().strip();
        if (target.isEmpty() || target.chars().anyMatch(c -> c < 0x20 || c == '<' || c == '>')) {
            return Optional.empty();
        }
        var scheme = SCHEME.matcher(target);
        if (scheme.find()) {
            String name = scheme.group(1).toLowerCase(Locale.ROOT);
            if (!name.equals("http") && !name.equals("https") && !name.equals("mailto") && !name.equals("ftp")) {
                return Optional.empty();
            }
        }
        return Optional.of(target.contains(" ") || target.contains("(") || target.contains(")")
                ? "<" + target + ">" : target);
    }

    /** Maskiert Auszeichnungszeichen; am Zeilenanfang auch Zeichen, die einen Block eröffnen. */
    static String escape(String text, boolean lineStart) {
        String escaped = SPECIAL.matcher(text).replaceAll(match -> "\\\\" + java.util.regex.Matcher.quoteReplacement(
                match.group()));
        if (lineStart) {
            var start = LINE_START.matcher(escaped);
            if (start.find()) {
                String marker = start.group(2);
                String masked = Character.isDigit(marker.charAt(0))
                        ? marker.substring(0, marker.length() - 1) + "\\" + marker.charAt(marker.length() - 1)
                        : "\\" + marker;
                escaped = start.group(1) + masked + escaped.substring(start.end());
            }
        }
        return escaped;
    }

    private static String describe(Location location) {
        if (location.sheet().isPresent()) {
            // Kein "--" und kein ">" im Kommentar, damit der Blattname ihn nicht beendet.
            return "sheet: " + location.sheet().get().replace("--", "- -").replace(">", "&gt;").replaceAll("\\s+", " ");
        }
        if (location.slide().isPresent()) {
            return "slide " + location.slide().get();
        }
        return "page " + location.page().orElseThrow();
    }
}
