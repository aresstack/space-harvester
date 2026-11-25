package com.aresstack.spaceharvester.mainframe.outline;

import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.model.JclOutlineModel;
import com.aresstack.spaceharvester.mainframe.parser.AntlrJclParser;
import com.aresstack.spaceharvester.mainframe.parser.CobolParser;
import com.aresstack.spaceharvester.mainframe.parser.DdmOutlineBuilder;
import com.aresstack.spaceharvester.mainframe.parser.NaturalParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.aresstack.spaceharvester.mainframe.model.JclElementType.*;

/**
 * Gliederung eines Quelltexts, so wie sie das Outline von MainframeMate ({@code JclOutlinePanel}) zeigt: Gruppen je
 * Sprache bei „Alle“, sonst flach gefiltert, und eine Zählzeile. Ohne Swing, damit jede Oberfläche sie zeigen kann.
 */
public final class Outline {

    /** Filter, der alles zeigt (gruppiert). */
    public static final String ALL = "Alle";

    /** Eine Gruppe der Gliederung (z. B. „Steps“) mit ihren Elementen. */
    public record Group(String label, List<JclElement> elements) {
    }

    private record Bucket(String label, Set<JclElementType> types) {
    }

    private static final Map<SourceLanguage, List<Bucket>> BUCKETS = Map.of(
            SourceLanguage.JCL, List.of(
                    new Bucket("Jobs", EnumSet.of(JOB)),
                    new Bucket("Steps", EnumSet.of(EXEC)),
                    new Bucket("DDs", EnumSet.of(DD)),
                    new Bucket("Prozeduren", EnumSet.of(PROC, PEND))),
            SourceLanguage.COBOL, List.of(
                    new Bucket("Divisions", EnumSet.of(DIVISION, PROCEDURE_DIVISION)),
                    new Bucket("Sections", EnumSet.of(SECTION, WORKING_STORAGE, LINKAGE_SECTION, FILE_SECTION,
                            SCREEN_SECTION)),
                    new Bucket("Paragraphs", EnumSet.of(PARAGRAPH)),
                    new Bucket("Data Items", EnumSet.of(LEVEL_01, LEVEL_77, LEVEL_88, DATA_ITEM)),
                    new Bucket("Calls & Performs", EnumSet.of(CALL_STMT, DYNAMIC_CALL_STMT, DEBUG_CALL_STMT, PERFORM_STMT))),
            SourceLanguage.NATURAL, List.of(
                    new Bucket("DEFINE DATA", EnumSet.of(NAT_DEFINE_DATA, NAT_LOCAL, NAT_PARAMETER, NAT_GLOBAL,
                            NAT_INDEPENDENT, NAT_DATA_VAR, NAT_DATA_VIEW, NAT_DATA_REDEFINE, NAT_DATA_CONST)),
                    new Bucket("Subroutines", EnumSet.of(NAT_SUBROUTINE, NAT_INLINE_SUBROUTINE)),
                    new Bucket("Calls", EnumSet.of(NAT_CALLNAT, NAT_CALL, NAT_FETCH, NAT_PERFORM)),
                    new Bucket("DB Zugriffe", EnumSet.of(NAT_READ, NAT_FIND, NAT_HISTOGRAM, NAT_STORE, NAT_UPDATE,
                            NAT_DELETE, NAT_GET)),
                    new Bucket("Kontrollfluss", EnumSet.of(NAT_DECIDE, NAT_IF_BLOCK, NAT_FOR, NAT_REPEAT,
                            NAT_ON_ERROR)),
                    new Bucket("I/O", EnumSet.of(NAT_INPUT, NAT_WRITE, NAT_DISPLAY, NAT_PRINT))),
            SourceLanguage.DDM, List.of());

    /** Filtername (wie die Auswahl in MainframeMate) → Bucket-Index; „I/O“ hatte dort keinen eigenen Filter. */
    private static final Map<SourceLanguage, Map<String, Integer>> FILTERS = Map.of(
            SourceLanguage.JCL, ordered("Jobs", 0, "Steps (EXEC)", 1, "DDs", 2, "Prozeduren", 3),
            SourceLanguage.COBOL, ordered("Divisions", 0, "Sections", 1, "Paragraphs", 2, "Data Items", 3,
                    "Calls/Performs", 4),
            SourceLanguage.NATURAL, ordered("DEFINE DATA", 0, "Subroutines", 1, "Calls", 2, "DB Zugriffe", 3,
                    "Kontrollfluss", 4),
            SourceLanguage.DDM, ordered());

    private final SourceLanguage language;
    private final JclOutlineModel model;

    private Outline(SourceLanguage language, JclOutlineModel model) {
        this.language = language;
        this.model = model;
    }

    /** Parst {@code content} als {@code language} mit den Parsern aus MainframeMate. */
    public static Outline parse(SourceLanguage language, String content, String sourceName) {
        Objects.requireNonNull(language, "language");
        String text = content == null ? "" : content;
        JclOutlineModel model = switch (language) {
            case JCL -> new AntlrJclParser().parse(text, sourceName);
            case COBOL -> new CobolParser().parse(text, sourceName);
            case NATURAL -> new NaturalParser().parse(text, sourceName);
            case DDM -> new DdmOutlineBuilder().parse(text, sourceName);
        };
        return new Outline(language, model);
    }

    public static Outline fromCobolAnalysis(com.aresstack.spaceharvester.mainframe.cobol.CobolSource source,
            com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis analysis) {
        return new Outline(SourceLanguage.COBOL,
                new com.aresstack.spaceharvester.mainframe.parser.CobolOutlineProjection().project(source, analysis));
    }

    public SourceLanguage language() {
        return language;
    }

    public JclOutlineModel model() {
        return model;
    }

    public boolean isEmpty() {
        return model.isEmpty();
    }

    /** Die Filter dieser Sprache, zuerst {@link #ALL}. */
    public List<String> filters() {
        List<String> names = new ArrayList<>();
        names.add(ALL);
        names.addAll(FILTERS.get(language).keySet());
        return List.copyOf(names);
    }

    /**
     * Die Gruppen für {@code filter}: bei {@link #ALL} gruppiert wie MainframeMate (leere Gruppen fallen weg, Rest
     * unter „⚙ Andere“; DDM ungruppiert), sonst eine einzige Gruppe ohne Überschrift mit den passenden Elementen.
     */
    public List<Group> groups(String filter) {
        List<JclElement> all = model.getElements();
        List<Bucket> buckets = BUCKETS.get(language);
        if (filter == null || ALL.equals(filter)) {
            if (buckets.isEmpty()) {
                return List.of(new Group("", all));
            }
            List<Group> groups = new ArrayList<>();
            List<JclElement> others = new ArrayList<>();
            List<List<JclElement>> filled = new ArrayList<>();
            buckets.forEach(b -> filled.add(new ArrayList<>()));
            for (JclElement element : all) {
                int index = indexOf(buckets, element.getType());
                (index < 0 ? others : filled.get(index)).add(element);
            }
            for (int i = 0; i < buckets.size(); i++) {
                if (!filled.get(i).isEmpty()) {
                    groups.add(new Group(buckets.get(i).label(), List.copyOf(filled.get(i))));
                }
            }
            if (!others.isEmpty()) {
                groups.add(new Group("Andere", List.copyOf(others)));
            }
            return List.copyOf(groups);
        }
        Integer index = FILTERS.get(language).get(filter);
        if (index == null) {
            return List.of(new Group("", all));
        }
        Set<JclElementType> types = buckets.get(index).types();
        return List.of(new Group("", all.stream().filter(e -> types.contains(e.getType())).toList()));
    }

    /** Die Zählzeile unter der Gliederung, Wortlaut wie in MainframeMate. */
    public String status() {
        String suffix = model.getAnalysisDiagnostics().isEmpty() ? ""
                : " · Analyse unvollständig: " + model.getAnalysisDiagnostics().getFirst();
        if (model.isEmpty()) {
            return "Keine Struktur erkannt" + suffix;
        }
        int total = model.getElementCount();
        String counts = switch (language) {
            case COBOL -> String.format("%d Div, %d Sec, %d Para, %d Elemente", model.getDivisions().size(),
                    model.getSections().size(), model.getParagraphs().size(), total);
            case NATURAL -> String.format("%d Subs, %d Calls, %d DB, %d Elemente", model.getSubroutines().size(),
                    model.getNaturalCalls().size(), model.getNaturalDbOps().size(), total);
            case JCL -> String.format("%d Jobs, %d Steps, %d Elemente", model.getJobs().size(),
                    model.getSteps().size(), total);
            case DDM -> String.format("%d Elemente", total);
        };
        return counts + suffix;
    }

    /** Überschrift wie „📑 JCL Outline“ in MainframeMate. */
    public String title() {
        return switch (language) {
            case NATURAL -> "Natural-Gliederung";
            case COBOL -> "COBOL-Gliederung";
            case DDM -> "DDM-Gliederung";
            case JCL -> "JCL-Gliederung";
        };
    }

    private static int indexOf(List<Bucket> buckets, JclElementType type) {
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).types().contains(type)) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Integer> ordered(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }
}