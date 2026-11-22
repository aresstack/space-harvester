package com.aresstack.spaceharvester.application.podcast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tabellen im Seitentext für den Erklär-Podcast (Angelo 21.11.2026: „nicht, dass die anfangen, jede Tabelle vorzulesen,
 * wo nur stumpfe Zahlen drin sind; es gibt Tabellen, die sind informativ“). Der Seitentext trägt eine Tabellenzeile als
 * eine Zeile mit {@code " | "} zwischen den Zellen. Eine Tabelle, deren Zellen überwiegend Zahlen, Datumsangaben,
 * Kennungen oder leer sind, fällt weg; übrig bleibt ein Hinweis mit ihren Spaltenköpfen, damit das Modell weiß, dass es
 * sie gibt. Informative Tabellen (Text in den Zellen) bleiben, lange auf ihre ersten Zeilen gekürzt.
 */
final class PodcastTables {

    /** Ab diesem Anteil stumpfer Zellen fällt eine Tabelle weg. */
    static final double DULL_SHARE = 0.5;
    /** So viele Zeilen einer informativen Tabelle bleiben stehen. */
    static final int MAX_ROWS = 12;

    /** Zahlen, Beträge, Prozente, Datum, Uhrzeit, Versionsnummern, kurze Kennungen wie {@code ABC-123}. */
    private static final Pattern DULL = Pattern.compile(
            "[-+±~≈]?[\\d.,:/ ]*\\d[\\d.,:/ ]*\\s*(%|€|EUR|MB|GB|KB|TB|ms|s|h|min|Std\\.?|Stk\\.?|x)?"
                    + "|[A-Z]{1,6}[-_]?\\d+[\\w.-]*|v?\\d+(\\.\\d+)+\\w*|[-–—/x✓✔✗✘?]|ja|nein|j|n|ok|n/a");

    private PodcastTables() {
    }

    /** Der Text mit gekürzten oder ausgelassenen Tabellen; sonst unverändert. */
    static String prune(String text) {
        if (text == null || !text.contains(" | ")) {
            return text == null ? "" : text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < lines.length) {
            if (!row(lines[i])) {
                append(out, lines[i]);
                i++;
                continue;
            }
            List<String> table = new ArrayList<>();
            while (i < lines.length && row(lines[i])) {
                table.add(lines[i]);
                i++;
            }
            for (String line : table(table)) {
                append(out, line);
            }
        }
        out.setLength(out.length() - 1); // der Umbruch nach der letzten Zeile
        return out.toString();
    }

    private static List<String> table(List<String> rows) {
        if (rows.size() < 2) {
            return rows;
        }
        int cells = 0;
        int dull = 0;
        for (String row : rows.subList(1, rows.size())) {
            for (String cell : row.split(" \\| ", -1)) {
                cells++;
                if (dull(cell)) {
                    dull++;
                }
            }
        }
        if (cells > 0 && dull >= cells * DULL_SHARE) {
            return List.of("(Tabelle mit Zahlen ausgelassen, Spalten: " + rows.get(0).replace(" | ", ", ").strip()
                    + ")");
        }
        if (rows.size() <= MAX_ROWS + 1) {
            return rows;
        }
        List<String> kept = new ArrayList<>(rows.subList(0, MAX_ROWS + 1));
        kept.add("(… weitere Zeilen der Tabelle ausgelassen)");
        return kept;
    }

    static boolean dull(String cell) {
        String value = cell.strip();
        return value.isEmpty() || DULL.matcher(value).matches();
    }

    private static boolean row(String line) {
        return line.contains(" | ");
    }

    private static void append(StringBuilder out, String line) {
        out.append(line).append('\n');
    }
}
