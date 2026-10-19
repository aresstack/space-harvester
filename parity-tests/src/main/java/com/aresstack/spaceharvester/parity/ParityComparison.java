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
     * Ausgeführte Makros im Export (#218): wie viele der Sätze, die nur ein ausgeführtes {@code include} oder
     * {@code excerpt-include} in die Seite bringt, im Export stehen, und welche Makros der Core als nicht ausgeführt
     * gemeldet hat ({@code OMITTED_CONTENT}). Der Enterprise AI Client meldet nichts; er schreibt die Ansicht.
     * Meldungen mit {@value #VIEW_COUNT_MISMATCH} sind gewollt anders (PARITY.md, #426): Zählt die Ansicht andere
     * äußerste Makros als der Storage, setzt der Core nichts ungeprüft ein, der EAC übernimmt die Ansicht trotzdem.
     * Sie stehen im Detail, sind aber keine Lücke.
     *
     * @param omitted die Meldungen des Core über nicht ausgeführte Makros, je eine Zeile
     */
    public static Finding macros(String format, DocumentOutline eac, DocumentOutline core, List<String> sentences,
                                 List<String> omitted) {
        long expected = sentences.stream().filter(sentence -> contains(eac, sentence)).count();
        List<String> missing = sentences.stream().filter(sentence -> contains(eac, sentence)
                && !contains(core, sentence)).toList();
        long found = expected - missing.size();
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add("fehlt: " + listed(missing));
        }
        List<String> intended = omitted.stream().filter(line -> line.endsWith("(" + VIEW_COUNT_MISMATCH + ")"))
                .toList();
        List<String> gaps = omitted.stream().filter(line -> !intended.contains(line)).toList();
        if (!gaps.isEmpty()) {
            problems.add("nicht ausgeführt: " + listed(gaps));
        }
        String detail = String.join("; ", problems);
        if (!intended.isEmpty()) {
            detail += (detail.isEmpty() ? "" : "; ") + "gewollt anders: " + listed(intended);
        }
        return new Finding(format, "makros", "Ausgeführte Makros (include, excerpt-include, children)",
                expected + " Sätze", found + " Sätze" + (omitted.isEmpty() ? "" : ", " + omitted.size() + " Hinweise"),
                problems.isEmpty() ? Verdict.GLEICH : Verdict.CORE_WENIGER, detail);
    }

    /** Grund einer gewollt nicht ausgeführten Ansicht: Ansicht und Storage zählen verschieden viele Makros. */
    static final String VIEW_COUNT_MISMATCH = "view-count-mismatch";

    private static boolean contains(DocumentOutline outline, String sentence) {
        return outline.text().replaceAll("\\s+", " ").contains(sentence);
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

    /**
     * Adressformen (#178): je Form die Seite, auf die sie führt ({@code -}: keine). Führt eine Form beim Enterprise AI
     * Client auf eine Seite und im Core auf keine oder eine andere, ist das eine Lücke; führt sie nur im Core auf eine
     * Seite, kann der Core mehr.
     */
    public static List<Finding> addresses(Map<String, String> eac, Map<String, String> core) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : eac.entrySet()) {
            String expected = entry.getValue();
            String actual = core.getOrDefault(entry.getKey(), "fehlt");
            Verdict verdict = !core.containsKey(entry.getKey()) ? Verdict.CORE_WENIGER
                    : expected.equals(actual) ? Verdict.GLEICH
                    : expected.equals("-") ? Verdict.CORE_MEHR : Verdict.CORE_WENIGER;
            findings.add(new Finding("adressen", entry.getKey(), "Adresse " + entry.getKey(), expected, actual,
                    verdict, verdict == Verdict.CORE_WENIGER ? "andere oder keine Seite im Core" : ""));
        }
        return findings;
    }

    /**
     * Suche (#180): je Suche über eine Quelle ohne eigene Suche die gefundenen Dateien ({@code -}: keine) und ob jeder
     * Auszug den Suchbegriff zeigt ({@code ja}/{@code nein}), je Live-Treffer, ob er vorgeladen wurde. Findet der Core
     * eine Datei nicht, fehlt ihm ein Auszug oder lädt er einen Treffer nicht vor, ist das eine Lücke; findet er
     * zusätzlich etwas, kann er mehr. Fehlt ein Ergebnis im Core ganz, ist das ebenfalls eine Lücke.
     */
    public static List<Finding> search(Map<String, String> eac, Map<String, String> core) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : eac.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = core.getOrDefault(key, "fehlt");
            Verdict verdict;
            if (!core.containsKey(key)) {
                verdict = Verdict.CORE_WENIGER;
            } else if (expected.equals(actual)) {
                verdict = Verdict.GLEICH;
            } else if (yesNo(expected) && yesNo(actual)) {
                verdict = expected.equals("ja") ? Verdict.CORE_WENIGER : Verdict.CORE_MEHR;
            } else {
                verdict = items(actual).containsAll(items(expected)) ? Verdict.CORE_MEHR : Verdict.CORE_WENIGER;
            }
            String title = key.startsWith("prefetch.") ? "Live-Treffer " + key.substring("prefetch.".length())
                    + " vorgeladen"
                    : key.endsWith(".snippet") ? "Auszug zeigt den Suchbegriff: "
                    + key.substring("search.".length(), key.length() - ".snippet".length())
                    : "Treffer: " + key.substring(key.indexOf('.') + 1);
            findings.add(new Finding("suche", key, title, expected, actual, verdict,
                    verdict == Verdict.CORE_WENIGER ? "im Core weniger oder nicht gemessen" : ""));
        }
        return findings;
    }

    /**
     * Typerkennung und Dokumenttext (#183): je Datei der Typ nach dem Inhalt allein ({@code <datei>.content-type}) und
     * der gelesene Text (wie {@code text}, mit dem Typ nach Name und Inhalt extrahiert). Erkennt der Core einen anderen
     * Typ oder fehlen ihm Wörter des Maßstabs, ist das eine Lücke; fehlt eine Datei im Core ganz, ebenfalls.
     *
     * @param eacTypes  {@code <datei>.content-type} beim Enterprise AI Client
     * @param coreTypes dasselbe im Core ({@code -}: kein Typ erkannt)
     * @param eacTexts  Text je Datei beim Enterprise AI Client
     * @param coreTexts Text je Datei im Core; fehlt eine Datei, hat der Core keinen Text geliefert
     */
    public static List<Finding> documents(Map<String, String> eacTypes, Map<String, String> coreTypes,
                                          Map<String, String> eacTexts, Map<String, String> coreTexts) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : eacTexts.entrySet()) {
            String file = entry.getKey();
            String format = "dokument-" + file;
            String expected = eacTypes.getOrDefault(file + ".content-type", "-");
            String actual = coreTypes.getOrDefault(file + ".content-type", "fehlt");
            findings.add(new Finding(format, "typ", "Typ von " + file + " nach dem Inhalt", expected, actual,
                    expected.equals(actual) ? Verdict.GLEICH : Verdict.CORE_WENIGER,
                    expected.equals(actual) ? "" : "anderer oder kein Typ im Core"));
            if (!coreTexts.containsKey(file)) {
                findings.add(new Finding(format, "text", "Text von " + file, "vorhanden", "fehlt",
                        Verdict.CORE_WENIGER, "kein Text im Core"));
                continue;
            }
            Finding text = text(format, readingOutline(List.of(entry.getValue())),
                    readingOutline(List.of(coreTexts.get(file))));
            findings.add(new Finding(text.format(), text.check(), "Text von " + file, text.eac(), text.core(),
                    text.verdict(), text.detail()));
        }
        return findings;
    }

    private static boolean yesNo(String value) {
        return value.equals("ja") || value.equals("nein");
    }

    private static Set<String> items(String value) {
        return value.equals("-") ? Set.of() : new java.util.TreeSet<>(List.of(value.split(",")));
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

    /**
     * Akronym-Fakten (#182): je Datei die Kurzformen ({@code facts.<datei>.candidates}) und Ausschreibungen
     * ({@code facts.<datei>.definitions}, {@code KURZ=lang}), {@code -} ohne Fakten. Fehlt dem Core ein Eintrag des
     * Maßstabs, ist das eine Lücke; hat er mehr, steht es als „Core mehr“ im Bericht.
     */
    public static List<Finding> facts(Map<String, String> eac, Map<String, String> core) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : eac.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = core.getOrDefault(key, "fehlt");
            Set<String> wanted = expected.equals("-") ? Set.of() : items(expected);
            Set<String> got = actual.equals("-") || actual.equals("fehlt") ? Set.of() : items(actual);
            Verdict verdict = !core.containsKey(key) || !got.containsAll(wanted) ? Verdict.CORE_WENIGER
                    : got.size() > wanted.size() ? Verdict.CORE_MEHR : Verdict.GLEICH;
            String file = key.substring("facts.".length(), key.lastIndexOf('.'));
            String title = (key.endsWith(".definitions") ? "Ausschreibungen in " : "Akronyme in ") + file;
            findings.add(new Finding("fakten", key.substring("facts.".length()), title, expected, actual, verdict,
                    verdict == Verdict.CORE_WENIGER ? "im Core weniger oder nicht gemessen" : ""));
        }
        return findings;
    }

    /**
     * Git-Quelle (#187): je Stand ({@code main}, {@code tag}) die gelisteten Pfade (Core ⊇ EAC), der Titel je Pfad
     * (gleich) und die Sätze im Inhalt je Pfad (Core ⊇ EAC). Ein fehlender Pfad oder Satz ist eine Lücke.
     */
    public static List<Finding> git(Map<String, String> eac, Map<String, String> core) {
        return sourceFiles("git", eac, core);
    }

    /**
     * Dateiquelle (#193) wie {@link #git}: je Umfang ({@code all} mit Ausschluss, {@code start} ab Startpunkt) die
     * gelisteten Pfade (Core ⊇ EAC), der Titel je Pfad (gleich) und die Sätze im Inhalt je Pfad (Core ⊇ EAC).
     */
    public static List<Finding> files(Map<String, String> eac, Map<String, String> core) {
        return sourceFiles("files", eac, core);
    }

    /**
     * FTP-Quelle (#193) wie {@link #git}: je Tiefe ({@code deep}, {@code shallow}) die gelisteten Pfade (Core ⊇ EAC),
     * der Titel je Pfad (gleich) und die Sätze im Inhalt je Pfad (Core ⊇ EAC).
     */
    public static List<Finding> ftp(Map<String, String> eac, Map<String, String> core) {
        return sourceFiles("ftp", eac, core);
    }

    /**
     * SharePoint-Quelle (#193) wie {@link #git}: je Tiefe ({@code deep}, {@code shallow}) die gelisteten Pfade
     * (Core ⊇ EAC), der Titel je Pfad (gleich) und die Sätze im Inhalt je Pfad (Core ⊇ EAC); Leerzeichen im Pfad
     * stehen im Schlüssel als {@code _}.
     */
    public static List<Finding> sharePoint(Map<String, String> eac, Map<String, String> core) {
        return sourceFiles("sharepoint", eac, core);
    }

    /**
     * BetaView-Quelle (#193) wie {@link #git}: die Dokumente der Ergebnisliste (Core ⊇ EAC), der Titel je Dokument
     * (gleich) und die Sätze im Inhalt je Dokument (Core ⊇ EAC).
     */
    public static List<Finding> betaView(Map<String, String> eac, Map<String, String> core) {
        return sourceFiles("betaview", eac, core);
    }

    /**
     * JES-Quelle (#193) wie {@link #git}: je Filter ({@code all}, {@code zauf}) die Jobs (Core ⊇ EAC), der Titel je Job
     * (gleich) und die Sätze in der Ausgabe je Job (Core ⊇ EAC).
     */
    public static List<Finding> jes(Map<String, String> eac, Map<String, String> core) {
        return sourceFiles("jes", eac, core);
    }

    private static List<Finding> sourceFiles(String format, Map<String, String> eac, Map<String, String> core) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : eac.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = core.getOrDefault(key, "fehlt");
            String check = key.substring(format.length() + 1);
            String state = check.substring(0, check.indexOf('.'));
            String rest = check.substring(state.length() + 1);
            Verdict verdict;
            String title;
            if (rest.startsWith("title.")) {
                verdict = expected.equals(actual) ? Verdict.GLEICH : Verdict.CORE_WENIGER;
                title = "Titel von " + rest.substring("title.".length()) + " (" + state + ")";
            } else {
                Set<String> wanted = expected.equals("-") ? Set.of() : items(expected);
                Set<String> got = actual.equals("-") || actual.equals("fehlt") ? Set.of() : items(actual);
                verdict = !core.containsKey(key) || !got.containsAll(wanted) ? Verdict.CORE_WENIGER
                        : got.size() > wanted.size() ? Verdict.CORE_MEHR : Verdict.GLEICH;
                title = rest.equals("documents") ? "Dokumente (" + state + ")"
                        : "Sätze in " + rest.substring("sentences.".length()) + " (" + state + ")";
            }
            findings.add(new Finding(format, check, title, expected, actual, verdict,
                    verdict == Verdict.CORE_WENIGER ? "im Core weniger oder anders" : ""));
        }
        return findings;
    }

    /**
     * Suchlauf-Protokoll (#182) der Live-Suche: Suchtext, Zahl der gefragten und der ausgefallenen Quellen und der
     * erhaltenen Kandidaten. Jede Abweichung ist eine Lücke, denn ein Protokoll soll zeigen, was die Suche bekam.
     */
    public static List<Finding> searchRuns(Map<String, String> eac, Map<String, String> core) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : eac.entrySet()) {
            String expected = entry.getValue();
            String actual = core.getOrDefault(entry.getKey(), "fehlt");
            boolean same = expected.equals(actual);
            findings.add(new Finding("suchlauf", entry.getKey().substring("run.".length()),
                    RUNS.getOrDefault(entry.getKey(), entry.getKey()), expected, actual,
                    same ? Verdict.GLEICH : Verdict.CORE_WENIGER, same ? "" : "anderes Protokoll im Core"));
        }
        return findings;
    }

    private static final Map<String, String> RUNS = Map.of(
            "run.live.query", "Suchtext im Protokoll",
            "run.live.sources", "Gefragte Quellen",
            "run.live.failures", "Ausgefallene Quellen",
            "run.live.candidates", "Erhaltene Kandidaten");

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
