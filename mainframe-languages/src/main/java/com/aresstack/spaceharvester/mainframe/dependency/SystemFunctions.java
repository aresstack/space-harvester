package com.aresstack.spaceharvester.mainframe.dependency;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bekannte z/OS-Systemprogramme (IDCAMS, IEFBR14, SORT …) mit Wikipedia-Artikel, wie
 * {@code SystemFunctionSettingsHelper} und {@code SystemFunctionEntry} in MainframeMate. In der JCL-Hierarchie wird ein
 * {@code PGM=} auf eine bekannte Systemfunktion blau markiert; ein Doppelklick öffnet den Artikel.
 */
public final class SystemFunctions {

    /** Ein Eintrag: Programmname, deutscher und englischer Artikel, Beschreibung. */
    public record Entry(String name, String wikiTitleDe, String wikiTitleEn, String description) {

        public Entry {
            name = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
            wikiTitleDe = blankToEmpty(wikiTitleDe);
            wikiTitleEn = blankToEmpty(wikiTitleEn);
            description = blankToEmpty(description);
        }
    }

    private final Map<String, Entry> byName = new LinkedHashMap<>();

    public SystemFunctions(List<Entry> entries) {
        for (Entry entry : entries) {
            if (!entry.name().isEmpty()) {
                byName.putIfAbsent(entry.name(), entry);
            }
        }
    }

    public static SystemFunctions defaults() {
        return new SystemFunctions(defaultEntries());
    }

    public List<Entry> entries() {
        return List.copyOf(byName.values());
    }

    public Optional<Entry> find(String program) {
        return program == null ? Optional.empty()
                : Optional.ofNullable(byName.get(program.trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * Wikipedia-Adresse wie {@code MainFrame.openSystemFunctionInWiki}: deutscher Artikel bevorzugt, sonst englischer,
     * sonst der Programmname im deutschen Wikipedia.
     */
    public URI wikipedia(String program) {
        Entry entry = find(program).orElse(null);
        String title;
        String base = "https://de.wikipedia.org/wiki/";
        if (entry != null && !entry.wikiTitleDe().isEmpty()) {
            title = entry.wikiTitleDe();
        } else if (entry != null && !entry.wikiTitleEn().isEmpty()) {
            title = entry.wikiTitleEn();
            base = "https://en.wikipedia.org/wiki/";
        } else {
            title = program == null ? "" : program.trim();
        }
        return URI.create(base + URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "_")
                .replace("%23", "#").replace("%2F", "/").replace("%28", "(").replace("%29", ")"));
    }

    /** Die Vorgaben aus {@code SystemFunctionSettingsHelper.getDefaults()} von MainframeMate. */
    public static List<Entry> defaultEntries() {
        List<Entry> d = new ArrayList<>();
        d.add(new Entry("IDCAMS", "Access_Method_Services", "Access_Method_Services",
                "Access Method Services — VSAM-Verwaltung (DEFINE, DELETE, REPRO, LISTCAT, ALTER)"));
        d.add(new Entry("IEFBR14", "IEFBR14", "IEFBR14",
                "Null-Programm — allokiert/löscht Datasets ohne Verarbeitung"));
        d.add(new Entry("SORT", "DFSORT", "DFSORT", "DFSORT — Sortieren, Mischen, Kopieren von Datasets"));
        d.add(new Entry("ICEMAN", "DFSORT", "DFSORT", "DFSORT ICEMAN — alternativer Aufrufer für DFSORT"));
        d.add(new Entry("SYNCSORT", "Syncsort", "Syncsort", "Syncsort — Sortier-Utility (Drittanbieter)"));
        d.add(new Entry("IKJEFT01", "Time_Sharing_Option", "Time_Sharing_Option",
                "TSO/E Batch — führt TSO-Befehle und REXX im Batch aus"));
        d.add(new Entry("IKJEFT1A", "Time_Sharing_Option", "Time_Sharing_Option",
                "TSO/E Batch (IKJEFT1A) — wie IKJEFT01, setzt RC=12 bei ABEND"));
        d.add(new Entry("IKJEFT1B", "Time_Sharing_Option", "Time_Sharing_Option",
                "TSO/E Batch (IKJEFT1B) — wie IKJEFT01, setzt RC=16 bei ABEND"));
        d.add(new Entry("IRXJCL", "REXX", "Rexx", "REXX im Batch — führt REXX-Prozeduren unter TSO aus"));
        d.add(new Entry("IEBGENER", "IEBGENER", "IEBGENER", "Dataset Copy/Print — kopiert sequentielle Datasets"));
        d.add(new Entry("IEBCOPY", "IEBCOPY", "IEBCOPY",
                "PDS Copy/Compress — kopiert und komprimiert PDS-Bibliotheken"));
        d.add(new Entry("IEBUPDTE", "IEBUPDTE", "IEBUPDTE",
                "Sequential Update — fügt Members in PDS ein oder aktualisiert sie"));
        d.add(new Entry("IEHPROGM", "IEHPROGM", "IEHPROGM",
                "Katalogverwaltung — Scratch, Rename, Catalog, Uncatalog"));
        d.add(new Entry("IEHLIST", "IEHLIST", "IEHLIST", "Catalog Listing — listet VTOC und Katalogeinträge"));
        d.add(new Entry("IEHMOVE", "IEHMOVE", "IEHMOVE", "Dataset Move/Copy — verschiebt oder kopiert Datasets"));
        d.add(new Entry("IEWL", "Linkage_Editor", "Linkage_editor",
                "Linkage Editor — bindet Object Modules zu Load Modules"));
        d.add(new Entry("HEWL", "Linkage_Editor", "Linkage_editor", "Linkage Editor (alias HEWL)"));
        d.add(new Entry("IGYCRCTL", "COBOL", "COBOL", "Enterprise COBOL Compiler"));
        d.add(new Entry("ASMA90", "Assembler_(Informatik)", "Assembly_language#Assembler",
                "High Level Assembler (HLASM)"));
        d.add(new Entry("CBCDRVR", "C_(Programmiersprache)", "C_(programming_language)",
                "z/OS XL C/C++ Compiler Driver"));
        d.add(new Entry("DSNTEP2", "Db2", "IBM_Db2",
                "DB2 Dynamic SQL Batch — führt SQL-Anweisungen im Batch aus"));
        d.add(new Entry("DSNUPROC", "Db2", "IBM_Db2",
                "DB2 Utilities — LOAD, UNLOAD, REORG, RUNSTATS, COPY, RECOVER"));
        d.add(new Entry("DSNTIAD", "Db2", "IBM_Db2", "DB2 Dynamic SQL Processor"));
        d.add(new Entry("DFHCSDUP", "Customer_Information_Control_System", "CICS",
                "CICS CSD Utility — verwaltet CICS System Definition Datasets"));
        d.add(new Entry("FTP", "File_Transfer_Protocol", "File_Transfer_Protocol",
                "z/OS FTP Client — Dateiübertragung"));
        d.add(new Entry("ICETOOL", "DFSORT", "DFSORT",
                "DFSORT ICETOOL — erweiterte Sortier- und Berichtsoperationen"));
        d.add(new Entry("ICHUT100", "Resource_Access_Control_Facility", "Resource_Access_Control_Facility",
                "RACF Utility — Datenbank-Entlade-Utility"));
        d.add(new Entry("ICHUT200", "Resource_Access_Control_Facility", "Resource_Access_Control_Facility",
                "RACF Utility — Cross-Reference-Utility"));
        d.add(new Entry("ADRDSSU", "DFSMSdss", "DFSMSdss",
                "DFSMSdss — Dump, Restore, Copy, Print von Datasets und Volumes"));
        d.add(new Entry("GIMSMP", "System_Modification_Program/Extended", "System_Modification_Program/Extended",
                "SMP/E — System Modification Program/Extended"));
        d.add(new Entry("ICKDSF", "ICKDSF", "ICKDSF",
                "Device Support Facilities — DASD-Initialisierung und -Verwaltung"));
        return d;
    }

    /** Zeilenweise Ablage {@code NAME<TAB>DE<TAB>EN<TAB>BESCHREIBUNG} (für den App-Zustand). */
    public String serialize() {
        StringBuilder out = new StringBuilder();
        for (Entry e : byName.values()) {
            out.append(clean(e.name())).append('\t').append(clean(e.wikiTitleDe())).append('\t')
                    .append(clean(e.wikiTitleEn())).append('\t').append(clean(e.description())).append('\n');
        }
        return out.toString();
    }

    /** Gegenstück zu {@link #serialize()}; {@code null} oder leer ergibt die Vorgaben. */
    public static SystemFunctions parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return defaults();
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : stored.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            entries.add(new Entry(parts[0], parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : "",
                    parts.length > 3 ? parts[3] : ""));
        }
        return new SystemFunctions(entries);
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String blankToEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
