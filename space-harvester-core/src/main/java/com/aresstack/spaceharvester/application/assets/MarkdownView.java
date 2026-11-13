package com.aresstack.spaceharvester.application.assets;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Pattern SPECIAL = Pattern.compile("[\\\\`*_\\[\\]<>|~&]");
    /** So viele nachgestellte Verweise hält die Ansicht im Speicher, weitere gehen in eine temporäre Datei. */
    static final int LINKS_IN_MEMORY = 1_000;
    private static final Pattern LINE_START = Pattern.compile("^(\\s*)([#+\\-=>]|\\d+[.)])");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):");

    private final Appendable out;
    private Block previous;
    private Optional<Location> location = Optional.empty();
    private int tableColumns;
    private boolean tableHeaderWritten;
    /** Die letzte Zeile einer Tabelle bleibt offen, bis eine neue Zeile beginnt oder die Tabelle endet. */
    private final List<Block.Cell> openRow = new ArrayList<>();
    /** Verweise des vorigen Tabellenstücks, deren Beschriftung in der offenen Zeile steht. */
    private final List<Link> openRowLinks = new ArrayList<>();
    /** Einrückung des Inhalts je Listenebene (Breite der Marken der Vorfahren). */
    private final List<Integer> listIndents = new ArrayList<>();
    private final List<Link> pendingLinks = new ArrayList<>();
    private final List<String> deferredLines = new ArrayList<>();
    private Path deferredFile;
    private BufferedWriter deferredWriter;

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
                closeTable();
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
                    if (!(previous instanceof Block.ListItem) || moved) {
                        listIndents.clear();
                    }
                    String marker = item.ordered() ? "1. " : "- ";
                    String indent = listIndent(item.level(), marker.length());
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
            closeTable();
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

    /**
     * Einrückung eines Listenpunkts: die Summe der Markenbreiten seiner Vorfahren, damit gemischte Listen
     * ({@code 1.} mit {@code -} darunter) verschachtelt bleiben. Fehlende Vorfahren zählen mit der eigenen Breite.
     */
    private String listIndent(int level, int markerWidth) {
        while (listIndents.size() > level - 1) {
            listIndents.removeLast();
        }
        while (listIndents.size() < level - 1) {
            listIndents.add(markerWidth);
        }
        int width = listIndents.stream().mapToInt(Integer::intValue).sum();
        listIndents.add(markerWidth);
        return " ".repeat(width);
    }

    /**
     * Ein Tabellenstück. Die letzte Zeile bleibt offen, weil das nächste Stück sie fortsetzen kann (die Ablage teilt
     * Tabellen auch innerhalb einer Zeile).
     */
    private void table(Block.Table table, boolean continued) throws IOException {
        if (!continued) {
            tableColumns = 1;
            tableHeaderWritten = false;
        }
        // Die Spaltenzahl steht mit der Kopfzeile fest; bis dahin zählen alle Zellen, die schon da sind.
        if (!tableHeaderWritten) {
            for (Block.Cell cell : table.cells()) {
                tableColumns = Math.max(tableColumns, cell.column() + 1);
            }
        }
        List<Link> unused = new ArrayList<>(openRowLinks);
        openRowLinks.clear();
        unused.addAll(table.links());
        for (Block.Cell cell : table.cells()) {
            if (!openRow.isEmpty() && openRow.getLast().row() != cell.row()) {
                writeRow(unused);
            }
            openRow.add(cell);
        }
        // Nur Verweise, die noch in der offenen Zeile stehen können, warten auf das nächste Stück.
        for (Link link : unused) {
            boolean inOpenRow = link.label().isPresent() && openRow.stream()
                    .anyMatch(cell -> cell.text().replaceAll("\\s+", " ").contains(link.label().get()));
            if (inOpenRow) {
                openRowLinks.add(link);
            } else {
                defer(link);
            }
        }
    }

    /** Schreibt die offene Zeile einer Tabelle und beendet die Tabelle. */
    private void closeTable() throws IOException {
        if (!openRow.isEmpty()) {
            List<Link> unused = new ArrayList<>(openRowLinks);
            openRowLinks.clear();
            writeRow(unused);
            unused.forEach(this::defer);
        }
    }

    private void writeRow(List<Link> unused) throws IOException {
        List<Block.Cell> cells = List.copyOf(openRow);
        openRow.clear();
        if (!tableHeaderWritten) {
            tableHeaderWritten = true;
            boolean header = cells.stream().allMatch(Block.Cell::header);
            out.append(row(header ? cells : List.of(), tableColumns, unused)).append('\n');
            out.append("|").append(" --- |".repeat(tableColumns));
            if (header) {
                return;
            }
        }
        out.append('\n').append(row(cells, tableColumns, unused));
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

    /** Merkt einen Verweis für die Zeile nach dem Block vor; über {@link #LINKS_IN_MEMORY} in einer Datei. */
    private void defer(Link link) {
        Optional<String> target = target(link);
        String label = escape(link.label().orElse(link.target()).replaceAll("\\s+", " ").strip(), false);
        String line = target.map(t -> "[" + label + "](" + t + ")").orElse(escape(link.target(), false));
        try {
            if (deferredWriter == null && deferredLines.size() < LINKS_IN_MEMORY) {
                deferredLines.add(line);
                return;
            }
            if (deferredWriter == null) {
                deferredFile = Files.createTempFile("markdown-verweise-", ".tmp");
                deferredWriter = Files.newBufferedWriter(deferredFile, StandardCharsets.UTF_8);
            }
            // Eine Zeile je Verweis: Ziel und Beschriftung enthalten keinen Zeilenumbruch mehr.
            deferredWriter.write(line.replaceAll("\\R", " "));
            deferredWriter.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void flushLinks() throws IOException {
        pendingLinks.forEach(this::defer);
        pendingLinks.clear();
        if (deferredLines.isEmpty()) {
            return;
        }
        out.append("\n\n").append(String.join("\\\n", deferredLines));
        deferredLines.clear();
        if (deferredWriter != null) {
            try {
                deferredWriter.close();
                try (BufferedReader reader = Files.newBufferedReader(deferredFile, StandardCharsets.UTF_8)) {
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        out.append("\\\n").append(line);
                    }
                }
            } finally {
                deferredWriter = null;
                Files.deleteIfExists(deferredFile);
                deferredFile = null;
            }
        }
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
