package com.aresstack.spaceharvester.parity;

import com.aresstack.spaceharvester.parity.DocumentOutline.Heading;
import com.aresstack.spaceharvester.parity.DocumentOutline.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vergleicht den Export des Enterprise AI Client (Maßstab) mit dem des Core (ADR 0012, Paritätsregel): Der Core darf
 * mehr können, nie weniger.
 */
public final class ParityComparison {

    /** Anteil der Wörter des Maßstabs, die im Core vorkommen müssen. */
    static final double TEXT_RECALL = 0.97;
    private static final int LISTED = 12;

    /** Ergebnis einer Prüfung. */
    public enum Verdict {
        /** Gleichwertig. */
        GLEICH,
        /** Der Core kann hier mehr. */
        CORE_MEHR,
        /** Der Core fällt zurück: eine Paritätslücke. */
        CORE_WENIGER,
        /** In diesem Format nicht messbar. */
        NICHT_MESSBAR
    }

    /**
     * Eine Prüfung.
     *
     * @param check  stabile Kennung, z. B. {@code bilder}; zusammen mit dem Format der Schlüssel in known-gaps
     * @param title  lesbarer Name
     * @param eac    Wert beim Enterprise AI Client
     * @param core   Wert beim Core
     * @param detail was fehlt oder was mehr ist; leer bei Gleichheit
     */
    public record Finding(String format, String check, String title, String eac, String core, Verdict verdict,
                          String detail) {

        public String key() {
            return format + "." + check;
        }
    }

    private ParityComparison() {
    }

    public static List<Finding> compare(DocumentOutline eac, DocumentOutline core) {
        if (!eac.format().equals(core.format())) {
            throw new IllegalArgumentException("Formate verschieden: " + eac.format() + " / " + core.format());
        }
        String format = eac.format();
        List<Finding> findings = new ArrayList<>();
        findings.add(chapters(format, eac, core));
        findings.add(depth(format, eac, core));
        if (!format.equals("md")) {
            findings.add(flag(format, "inhaltsverzeichnis", "Inhaltsverzeichnis", eac.tableOfContents(),
                    core.tableOfContents()));
            findings.add(flag(format, "seitenzahlen", "Seitenzahlen", eac.pageNumbers(), core.pageNumbers()));
        } else if (eac.tableOfContents() || core.tableOfContents()) {
            findings.add(flag(format, "inhaltsverzeichnis", "Inhaltsverzeichnis", eac.tableOfContents(),
                    core.tableOfContents()));
        }
        if (format.equals("docx")) {
            findings.add(flag(format, "felder-aktualisieren", "Felder beim Öffnen aktualisieren",
                    eac.updateFieldsOnOpen(), core.updateFieldsOnOpen()));
        }
        if (format.equals("pdf")) {
            findings.add(compareNumbers(format, "lesezeichen", "PDF-Lesezeichen", eac.outlineEntries(), core.outlineEntries()));
        }
        findings.add(compareNumbers(format, "bilder", "Eingebettete Bilder", eac.images(), core.images()));
        findings.add(imageNotes(format, eac, core));
        findings.add(tables(format, eac, core));
        findings.add(externalLinks(format, eac, core));
        findings.add(compareNumbers(format, "links-intern", "Interne Sprünge", eac.internalLinks(), core.internalLinks()));
        findings.add(compareNumbers(format, "mermaid-quelltext", "Mermaid-Quelltext", eac.mermaidSources(),
                core.mermaidSources()));
        findings.add(text(format, eac, core));
        return findings;
    }

    /**
     * Vergleicht die Leseansicht aller Seiten (#176): Seiten mit Ansicht, Lesetext (wie {@code text}) und Bilder.
     *
     * @param eac  Lesetext je Seite beim Enterprise AI Client
     * @param core Lesetext je Seite im Core; fehlt eine Seite, hat der Core keine Ansicht geliefert
     */
    public static List<Finding> readingViews(Map<String, String> eac, Map<String, String> core,
                                             Map<String, Integer> eacImages, Map<String, Integer> coreImages) {
        String format = "leseansicht";
        List<Finding> findings = new ArrayList<>();
        List<String> without = eac.keySet().stream().filter(page -> !core.containsKey(page)).toList();
        findings.add(new Finding(format, "seiten", "Seiten mit Leseansicht", Integer.toString(eac.size()),
                Integer.toString(eac.size() - without.size()),
                without.isEmpty() ? Verdict.GLEICH : Verdict.CORE_WENIGER,
                without.isEmpty() ? "" : "ohne Ansicht im Core: " + listed(without)));
        findings.add(text(format, readingOutline(eac.values()), readingOutline(core.values())));
        findings.add(compareNumbers(format, "bilder", "Bilder der Ansicht",
                eacImages.values().stream().mapToInt(Integer::intValue).sum(),
                coreImages.values().stream().mapToInt(Integer::intValue).sum()));
        return findings;
    }

    /**
     * Vergleicht die Navigation (#177). Beide Seiten liefern dieselben Schlüssel: {@code space.name},
     * {@code space.children} und je Seite {@code <seite>.children}, {@code <seite>.ancestors}, {@code <seite>.links},
     * jeweils als kommagetrennte Seiten-IDs. Kinder und Breadcrumbs müssen gleich sein (mit Reihenfolge), die
     * ausgehenden Verweise des Maßstabs müssen im Core enthalten sein.
     */
    public static List<Finding> navigation(Map<String, String> eac, Map<String, String> core) {
        String format = "navigation";
        List<Finding> findings = new ArrayList<>();
        String eacName = eac.getOrDefault("space.name", "");
        String coreName = core.getOrDefault("space.name", "");
        findings.add(new Finding(format, "space-name", "Name des Space", eacName, coreName,
                eacName.equals(coreName) ? Verdict.GLEICH : Verdict.CORE_WENIGER,
                eacName.equals(coreName) ? "" : "anderer Name im Core"));
        findings.add(navigationLists(format, "kinder", "Kindseiten in Reihenfolge", eac, core, ".children", false));
        findings.add(navigationLists(format, "breadcrumbs", "Breadcrumbs", eac, core, ".ancestors", false));
        findings.add(navigationLists(format, "querverweise", "Ausgehende Seitenverweise", eac, core, ".links", true));
        return findings;
    }

    /** Titel der Prüfpunkte der Quellenverwaltung (#188), in der Reihenfolge des Berichts. */
    private static final Map<String, String> MANAGEMENT = Map.ofEntries(
            Map.entry("validate.complete", "Vollständige Einstellungen sind gültig"),
            Map.entry("validate.missing-url", "Fehlende Basis-URL wird gemeldet"),
            Map.entry("validate.same-target", "Dasselbe Ziel ein zweites Mal wird abgelehnt"),
            Map.entry("test.reachable", "Verbindung testen: erreichbar"),
            Map.entry("test.unreachable", "Verbindung testen: nicht erreichbar"),
            Map.entry("test.incomplete", "Verbindung testen: unvollständige Einstellungen"),
            Map.entry("add.listed", "Hinzugefügte Quelle ist gelistet"),
            Map.entry("change.same-target.withdrawn", "Ändern ohne neues Ziel behält die Daten"),
            Map.entry("change.other-target.withdrawn", "Ändern auf ein neues Ziel zieht die Daten zurück"),
            Map.entry("disable", "Quelle abschalten, ohne sie zu entfernen"),
            Map.entry("remove.listed", "Entfernte Quelle ist nicht mehr gelistet"),
            Map.entry("remove.withdrawn", "Entfernen zieht die Daten zurück"));

    /**
     * Quellenverwaltung (#188): je Schritt des Szenarios das Ergebnis beider Seiten ({@code eac/management.properties}
     * gegen {@code Harvester.sourceManagement()}). Jedes andere Ergebnis ist eine Lücke.
     */
    public static List<Finding> sourceManagement(Map<String, String> eac, Map<String, String> core) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : eac.entrySet()) {
            String expected = entry.getValue();
            String actual = core.getOrDefault(entry.getKey(), "fehlt");
            boolean same = expected.equals(actual);
            findings.add(new Finding("quellen", entry.getKey(),
                    MANAGEMENT.getOrDefault(entry.getKey(), entry.getKey()), expected, actual,
                    same ? Verdict.GLEICH : Verdict.CORE_WENIGER, same ? "" : "anderes Ergebnis im Core"));
        }
        return findings;
    }

    private static Finding navigationLists(String format, String check, String title, Map<String, String> eac,
                                           Map<String, String> core, String suffix, boolean subset) {
        List<String> differing = new ArrayList<>();
        boolean more = false;
        int count = 0;
        for (Map.Entry<String, String> entry : eac.entrySet()) {
            if (!entry.getKey().endsWith(suffix)) {
                continue;
            }
            count++;
            List<String> expected = ids(entry.getValue());
            List<String> actual = ids(core.get(entry.getKey()));
            boolean same = subset ? actual.containsAll(expected) : actual.equals(expected);
            if (!same) {
                differing.add(entry.getKey().substring(0, entry.getKey().length() - suffix.length()) + " (EAC "
                        + String.join(",", expected) + ", Core " + (core.containsKey(entry.getKey())
                        ? String.join(",", actual) : "fehlt") + ")");
            } else if (subset && actual.size() > expected.size()) {
                more = true;
            }
        }
        Verdict verdict = !differing.isEmpty() ? Verdict.CORE_WENIGER : more ? Verdict.CORE_MEHR : Verdict.GLEICH;
        return new Finding(format, check, title, count + " Knoten", (count - differing.size()) + " gleich", verdict,
                differing.isEmpty() ? "" : "abweichend: " + listed(differing));
    }

    private static List<String> ids(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(",")).map(String::strip).filter(id -> !id.isEmpty()).toList();
    }

    private static DocumentOutline readingOutline(java.util.Collection<String> texts) {
        return new DocumentOutline("leseansicht", List.of(), false, false, false, 0, 0, 0, List.of(), Set.of(), 0, 0,
                String.join("\n", texts));
    }

    private static Finding chapters(String format, DocumentOutline eac, DocumentOutline core) {
        Map<String, Integer> remaining = counts(chapterTitles(core));
        List<String> missing = new ArrayList<>();
        for (String title : chapterTitles(eac)) {
            Integer left = remaining.get(title);
            if (left == null || left == 0) {
                missing.add(title);
            } else {
                remaining.put(title, left - 1);
            }
        }
        int extra = remaining.values().stream().mapToInt(Integer::intValue).sum();
        int eacCount = chapterTitles(eac).size();
        int coreCount = chapterTitles(core).size();
        Verdict verdict = !missing.isEmpty() ? Verdict.CORE_WENIGER : extra > 0 ? Verdict.CORE_MEHR : Verdict.GLEICH;
        String detail = !missing.isEmpty() ? "fehlt im Core: " + listed(missing)
                : extra > 0 ? extra + " zusätzliche Überschrift(en)" : "";
        return new Finding(format, "kapitel", "Kapitel und Überschriften", eacCount + " verschiedene",
                coreCount + " verschiedene", verdict, detail);
    }

    /** Überschriften ohne direkte Wiederholung (der Enterprise AI Client wiederholt den Seitentitel als H1). */
    private static List<String> chapterTitles(DocumentOutline outline) {
        List<String> titles = new ArrayList<>();
        String previous = null;
        for (Heading heading : outline.headings()) {
            String title = normalize(heading.text());
            if (!title.isEmpty() && !title.equals(previous)) {
                titles.add(title);
            }
            previous = title;
        }
        return titles;
    }

    private static Finding depth(String format, DocumentOutline eac, DocumentOutline core) {
        int eacDepth = maxLevel(eac);
        int coreDepth = maxLevel(core);
        return compareNumbers(format, "ebenen", "Tiefste Gliederungsebene", eacDepth, coreDepth);
    }

    /**
     * Tiefste Ebene ohne Wiederholungen: Der Enterprise AI Client wiederholt den Seitentitel eine Ebene tiefer als
     * Überschrift im Kapitelinhalt. Das ist Layout, keine zusätzliche Gliederungsebene (gewollte Abweichung).
     */
    private static int maxLevel(DocumentOutline outline) {
        int max = 0;
        Heading previous = null;
        for (Heading heading : outline.headings()) {
            boolean repeated = previous != null && heading.level() == previous.level() + 1
                    && normalize(heading.text()).equals(normalize(previous.text()));
            if (!repeated) {
                max = Math.max(max, heading.level());
            }
            previous = heading;
        }
        return max;
    }

    private static Finding flag(String format, String check, String title, boolean eac, boolean core) {
        Verdict verdict = eac == core ? Verdict.GLEICH : core ? Verdict.CORE_MEHR : Verdict.CORE_WENIGER;
        return new Finding(format, check, title, yesNo(eac), yesNo(core), verdict,
                verdict == Verdict.CORE_WENIGER ? "fehlt im Core" : "");
    }

    private static Finding compareNumbers(String format, String check, String title, int eac, int core) {
        Verdict verdict = eac == core ? Verdict.GLEICH : core > eac ? Verdict.CORE_MEHR : Verdict.CORE_WENIGER;
        return new Finding(format, check, title, Integer.toString(eac), Integer.toString(core), verdict,
                verdict == Verdict.CORE_WENIGER ? (eac - core) + " weniger im Core" : "");
    }

    /**
     * Fehlende Bilder müssen gemeldet werden: Jedes Bild des Maßstabs, eingebettet oder gemeldet, muss im Core
     * eingebettet oder gemeldet sein. Weniger Hinweise sind nur in Ordnung, wenn der Core dafür mehr einbettet.
     */
    private static Finding imageNotes(String format, DocumentOutline eac, DocumentOutline core) {
        Verdict verdict;
        String detail = "";
        int eacOutcomes = eac.images() + eac.imageNotes();
        int coreOutcomes = core.images() + core.imageNotes();
        if (core.imageNotes() < eac.imageNotes() && coreOutcomes < eacOutcomes) {
            verdict = Verdict.CORE_WENIGER;
            detail = (eacOutcomes - coreOutcomes) + " Bild(er) weder eingebettet noch gemeldet";
        } else {
            verdict = Verdict.GLEICH;
        }
        return new Finding(format, "bildhinweise", "Hinweise auf nicht eingebettete Bilder",
                Integer.toString(eac.imageNotes()), Integer.toString(core.imageNotes()), verdict, detail);
    }

    private static Finding tables(String format, DocumentOutline eac, DocumentOutline core) {
        if (eac.tables() == null || core.tables() == null) {
            return new Finding(format, "tabellen", "Tabellen", "–", "–", Verdict.NICHT_MESSBAR,
                    "im Format nicht erkennbar");
        }
        List<String> missing = new ArrayList<>();
        List<Table> pool = new ArrayList<>(core.tables());
        for (Table table : eac.tables()) {
            Table match = pool.stream().filter(candidate -> candidate.rows() == table.rows()
                    && candidate.columns() == table.columns()
                    && (candidate.headerRow() || !table.headerRow())).findFirst().orElse(null);
            if (match == null) {
                missing.add(table.rows() + "×" + table.columns() + (table.headerRow() ? " mit Kopfzeile" : ""));
            } else {
                pool.remove(match);
            }
        }
        Verdict verdict = !missing.isEmpty() ? Verdict.CORE_WENIGER
                : pool.isEmpty() ? Verdict.GLEICH : Verdict.CORE_MEHR;
        return new Finding(format, "tabellen", "Tabellen (Zeilen × Spalten, Kopfzeile)", describe(eac.tables()),
                describe(core.tables()), verdict, missing.isEmpty() ? "" : "fehlt im Core: " + listed(missing));
    }

    private static Finding externalLinks(String format, DocumentOutline eac, DocumentOutline core) {
        Set<String> missing = new LinkedHashSet<>(eac.externalLinks());
        missing.removeAll(core.externalLinks());
        Set<String> extra = new LinkedHashSet<>(core.externalLinks());
        extra.removeAll(eac.externalLinks());
        Verdict verdict = !missing.isEmpty() ? Verdict.CORE_WENIGER
                : extra.isEmpty() ? Verdict.GLEICH : Verdict.CORE_MEHR;
        String detail = !missing.isEmpty() ? "fehlt im Core: " + listed(List.copyOf(missing))
                : extra.isEmpty() ? "" : "zusätzlich im Core: " + listed(List.copyOf(extra));
        return new Finding(format, "links-extern", "Externe Links", Integer.toString(eac.externalLinks().size()),
                Integer.toString(core.externalLinks().size()), verdict, detail);
    }

    private static Finding text(String format, DocumentOutline eac, DocumentOutline core) {
        // Vorkommen zählen, nicht nur verschiedene Wörter: sonst fielen wiederholte Absätze nicht auf.
        // Wörter aus Überschriften zählen nur einmal: Überschriften prüft „kapitel“, und wie oft ein Exporter
        // einen Titel wiederholt (Kapitelinhalt, Kopfzeile), ist Layout (gewollte Abweichung, PARITY.md).
        Set<String> titleWords = new LinkedHashSet<>();
        for (DocumentOutline outline : List.of(eac, core)) {
            outline.headings().forEach(heading -> titleWords.addAll(words(heading.text())));
        }
        Map<String, Integer> eacWords = counts(words(eac.text()));
        Map<String, Integer> coreWords = counts(words(core.text()));
        titleWords.forEach(word -> {
            eacWords.computeIfPresent(word, (key, count) -> 1);
            coreWords.computeIfPresent(word, (key, count) -> 1);
        });
        int total = 0;
        int found = 0;
        Set<String> missing = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> word : eacWords.entrySet()) {
            int present = Math.min(word.getValue(), coreWords.getOrDefault(word.getKey(), 0));
            total += word.getValue();
            found += present;
            if (present < word.getValue()) {
                missing.add(word.getKey());
            }
        }
        int absent = (int) eacWords.keySet().stream().filter(word -> !coreWords.containsKey(word)).count();
        // Beide Maße: Vorkommen (wiederholte Absätze) und verschiedene Wörter (seltene Begriffe)
        double recall = Math.min(total == 0 ? 1.0 : (double) found / total,
                eacWords.isEmpty() ? 1.0 : 1.0 - (double) absent / eacWords.size());
        Verdict verdict = recall >= TEXT_RECALL ? Verdict.GLEICH : Verdict.CORE_WENIGER;
        String percent = String.format(Locale.ROOT, "%.1f %%", recall * 100);
        return new Finding(format, "text", "Text (Wörter des Maßstabs im Core)", total + " Wörter",
                percent, verdict, missing.isEmpty() ? "" : "fehlt oder seltener im Core: " + listed(List.copyOf(missing)));
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        String content = Patterns.CHROME_LINE.matcher(text).replaceAll("");
        for (String word : content.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            // Zahlen sind meist Seitenzahlen des Layouts
            if (word.length() > 1 && !word.chars().allMatch(Character::isDigit)) {
                words.add(word);
            }
        }
        return words;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    private static String describe(List<Table> tables) {
        if (tables.isEmpty()) {
            return "keine";
        }
        return tables.stream().map(table -> table.rows() + "×" + table.columns() + (table.headerRow() ? " K" : ""))
                .collect(Collectors.joining(", "));
    }

    private static String listed(List<String> values) {
        String shown = values.stream().limit(LISTED).collect(Collectors.joining(", "));
        return values.size() > LISTED ? shown + " … (" + values.size() + ")" : shown;
    }

    private static String yesNo(boolean value) {
        return value ? "ja" : "nein";
    }
}
