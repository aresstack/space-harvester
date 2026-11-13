package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.TextSection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lesetext eines abgelegten Abschnitts eines Dokuments (Anhang oder enthaltene Datei) als Eingabe der Prozessoren (ADR
 * 0026, AP9a): nur der Text der Blöcke in Lesereihenfolge, je Block eine Zeile wie bei der Projektion einer Seite. Kein
 * Kopf, kein Status, kein Fundstellenkommentar, kein Anzeigename; nichts, was das Dokument nicht selbst enthält.
 *
 * <ul>
 *   <li>Überschriften werden {@link PageText#sections()}, jede bis zur nächsten.</li>
 *   <li>Zeilenumbrüche innerhalb eines Absatzes, Listenpunkts oder einer Zelle werden Leerzeichen, damit ein Block eine
 *       Zeile bleibt; Code behält seine Zeilen.</li>
 *   <li>Eine Tabelle ergibt eine Zeile je Tabellenzeile, die Zellen getrennt durch {@value #CELL_SEPARATOR} wie bei der
 *       Projektion einer Confluence-Seite; so beginnt und endet jede Tabellenzeile als eigener Block, und kein Absatz
 *       reicht über die Grenze einer Tabelle.</li>
 * </ul>
 *
 * <p>Das Schema nennt Regeln, Extraktion und Version, aus der der Abschnitt stammt: Wird das Dokument mit einer anderen
 * Extraktion neu gelesen, ändert sich {@link PageText#inputSha256()}, und die Ergebnisse veralten.
 */
public final class DocumentSectionText {

    /** Regeln dieser Projektion; ändern sie sich, steigt die Version. */
    public static final String SCHEMA = "document-text/v1";
    static final String CELL_SEPARATOR = " | ";

    private DocumentSectionText() {
    }

    /** Schema eines Abschnitts aus dieser Extraktion. */
    public static String schema(ExtractionKey extraction) {
        return SCHEMA + "+" + extraction.extractor() + ":" + extraction.version() + ":" + extraction.model();
    }

    /** Der Lesetext eines Abschnitts. */
    public static PageText project(ExtractionKey extraction, TextSection section) {
        Objects.requireNonNull(extraction, "extraction");
        Objects.requireNonNull(section, "section");
        StringBuilder text = new StringBuilder();
        List<int[]> headings = new ArrayList<>();
        List<String> headingTexts = new ArrayList<>();
        for (Block block : section.blocks()) {
            String line = line(block);
            if (line.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            if (block instanceof Block.Heading heading) {
                headings.add(new int[] {text.length(), heading.level()});
                headingTexts.add(line);
            }
            text.append(line);
        }
        List<PageText.Section> sections = new ArrayList<>(headings.size());
        for (int h = 0; h < headings.size(); h++) {
            int start = headings.get(h)[0];
            int end = h + 1 < headings.size() ? headings.get(h + 1)[0] : text.length();
            sections.add(new PageText.Section(headingTexts.get(h), headings.get(h)[1], start, end));
        }
        return new PageText(schema(extraction), text.toString(), sections);
    }

    private static String line(Block block) {
        return switch (block) {
            case Block.Heading heading -> oneLine(heading.text());
            case Block.Paragraph paragraph -> oneLine(paragraph.text());
            case Block.ListItem item -> oneLine(item.text());
            case Block.Code code -> code.text().strip();
            case Block.Table table -> rows(table);
        };
    }

    private static String rows(Block.Table table) {
        StringBuilder rows = new StringBuilder();
        StringBuilder row = new StringBuilder();
        long current = -1;
        for (Block.Cell cell : table.cells()) {
            if (cell.row() != current) {
                appendRow(rows, row);
                row.setLength(0);
                current = cell.row();
            }
            String text = oneLine(cell.text());
            if (text.isEmpty()) {
                continue;
            }
            if (!row.isEmpty()) {
                row.append(CELL_SEPARATOR);
            }
            row.append(text);
        }
        appendRow(rows, row);
        return rows.toString();
    }

    private static void appendRow(StringBuilder rows, StringBuilder row) {
        if (row.isEmpty()) {
            return;
        }
        if (!rows.isEmpty()) {
            rows.append('\n');
        }
        rows.append(row);
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s*\\R\\s*", " ").strip();
    }
}
