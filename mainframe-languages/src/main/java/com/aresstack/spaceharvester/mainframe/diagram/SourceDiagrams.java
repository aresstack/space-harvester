package com.aresstack.spaceharvester.mainframe.diagram;

import com.aresstack.spaceharvester.mainframe.calltree.CallTreeNode;
import com.aresstack.spaceharvester.mainframe.calltree.CodeAnalyticsService;
import com.aresstack.spaceharvester.mainframe.calltree.SourceResolver;
import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.model.JclOutlineModel;
import com.aresstack.spaceharvester.mainframe.outline.Outline;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import com.aresstack.spaceharvester.mainframe.parser.DdmParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mermaid-Quelltext zu einem Großrechner-Quelltext, wie {@code SplitPreviewTab.parseMermaidFromOutline} in
 * MainframeMate: DDM immer als ER-Diagramm, sonst Gliederung parsen und in den gewählten Diagrammtyp umsetzen; die
 * Mindmap mit rekursivem Aufrufbaum bis zur gewählten Tiefe, das ER-Diagramm eines Natural-Programms mit den DDMs
 * seiner VIEWs. Bringt der gewählte Typ nichts, gilt „Struktur“. Ohne Swing, darf blockieren (Auflösen von Quellen).
 */
public final class SourceDiagrams {

    private static final Logger LOG = Logger.getLogger(SourceDiagrams.class.getName());

    /**
     * Ergebnis einer Umsetzung.
     *
     * @param mermaid   Mermaid-Quelltext, leer wenn nichts erzeugbar war
     * @param type      Typ, der tatsächlich erzeugt wurde (ER bei DDM, Struktur als Rückfall)
     * @param collapsed ob kompakt erzeugt wurde (große Modelle schalten das selbst ein)
     */
    public record Result(Optional<String> mermaid, DiagramType type, boolean collapsed) {
    }

    private SourceDiagrams() {
    }

    /** Ob der Text Mermaid-Quelltext ist (erstes Wort ein Mermaid-Diagrammtyp), wie in MainframeMate. */
    public static boolean isMermaid(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        String firstWord = content.trim().split("[\\s({]+")[0].toLowerCase(Locale.ROOT);
        for (String keyword : new String[] {"flowchart", "graph", "sequencediagram", "classdiagram", "erdiagram",
                "statediagram", "pie", "gantt", "gitgraph", "mindmap", "timeline", "sankey", "xychart", "block",
                "c4context", "c4container", "c4component", "c4deployment", "journey", "quadrantchart",
                "requirementdiagram", "packet"}) {
            if (firstWord.startsWith(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param content      Quelltext
     * @param sourceName   Name mit Endung (Sprache, DDM-Name)
     * @param language     gewählte oder erkannte Sprache; leer = an Name und Inhalt erkennen
     * @param type         gewünschter Diagrammtyp
     * @param mindmapDepth Tiefe des Aufrufbaums der Mindmap (0 = flach)
     * @param collapsed    kompakte Darstellung gewünscht
     * @param resolver     löst Aufrufziele und DDM-Namen zu Quelltext auf; {@code null} = keine Auflösung
     */
    public static Result generate(String content, String sourceName, Optional<SourceLanguage> language,
            DiagramType type, int mindmapDepth, boolean collapsed, SourceResolver resolver) {
        return generate(content, sourceName, language, type, mindmapDepth, collapsed, true, resolver);
    }

    /**
     * Wie oben; mit {@code autoCollapse = false} gilt {@code collapsed} genau so, wie verlangt, auch bei großen
     * Quelltexten (ausdrücklich „Details einblenden“, Export des vollständigen Diagramms).
     */
    public static Result generate(String content, String sourceName, Optional<SourceLanguage> language,
            DiagramType type, int mindmapDepth, boolean collapsed, boolean autoCollapse, SourceResolver resolver) {
        return generate(content, sourceName, language, type, mindmapDepth, collapsed, autoCollapse, resolver, null);
    }

    /** Use the resolved outline of the same source snapshot when available. */
    public static Result generate(String content, String sourceName, Optional<SourceLanguage> language,
            DiagramType type, int mindmapDepth, boolean collapsed, boolean autoCollapse, SourceResolver resolver,
            Outline preparedOutline) {
        if (content == null || content.isEmpty()) {
            return new Result(Optional.empty(), type, collapsed);
        }
        SourceLanguage lang = language.or(() -> SourceLanguage.detect(sourceName, content)).orElse(null);
        if (lang == null) {
            return new Result(Optional.empty(), type, collapsed);
        }

        // DDM: immer ER-Diagramm; scheitert das, mit der normalen Gliederung weiter
        if (lang == SourceLanguage.DDM) {
            DdmParser.DdmDefinition definition = new DdmParser().parse(content, ddmName(sourceName));
            if (definition != null) {
                String er = OutlineToMermaidConverter.convertDdmToErDiagram(definition);
                if (er != null) {
                    return new Result(Optional.of(er), DiagramType.ER_DIAGRAM, collapsed);
                }
            }
        }

        // COBOL: a flowchart shows only the static control flow of the parser, never paragraph order as execution
        // order (#1473). Without a complete graph (e.g. unresolved COPY) the structure diagram is shown instead.
        if (lang == SourceLanguage.COBOL && type == DiagramType.FLOWCHART) {
            String flow = cobolControlFlow(content, sourceName);
            if (flow != null) {
                return new Result(Optional.of(flow), DiagramType.FLOWCHART, collapsed);
            }
        }

        JclOutlineModel model = preparedOutline != null && preparedOutline.language() == lang
                ? preparedOutline.model() : Outline.parse(lang, content, sourceName).model();
        if (model == null || model.isEmpty()) {
            return new Result(Optional.empty(), type, collapsed);
        }

        CallTreeNode callTree = null;
        if (type == DiagramType.MINDMAP && mindmapDepth > 0) {
            try {
                CodeAnalyticsService analytics = CodeAnalyticsService.getInstance();
                if (lang == SourceLanguage.COBOL) {
                    callTree = analytics.buildCallTreeFromCalls(sourceName,
                            com.aresstack.spaceharvester.mainframe.calltree.SourceLanguage.COBOL,
                            cobolCalls(model), mindmapDepth, resolver);
                } else {
                    callTree = analytics.buildCallTree(content, sourceName, analytics.detectLanguage(content),
                            mindmapDepth, resolver);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Aufrufbaum gescheitert", e);
            }
        }

        List<DdmParser.DdmDefinition> ddms = null;
        if (type == DiagramType.ER_DIAGRAM && model.getLanguage() == JclOutlineModel.Language.NATURAL) {
            ddms = resolveDdms(model, resolver);
        }

        boolean compact = collapsed || autoCollapse && OutlineToMermaidConverter.shouldCollapse(model);
        String result = OutlineToMermaidConverter.convert(model, type, callTree, ddms, compact);
        DiagramType produced = type;
        if (result == null && type != DiagramType.STRUCTURE) {
            result = OutlineToMermaidConverter.convert(model, DiagramType.STRUCTURE, null, null, compact);
            produced = DiagramType.STRUCTURE;
        }
        return new Result(Optional.ofNullable(result).filter(s -> !s.trim().isEmpty()), produced, compact);
    }

    private static String cobolControlFlow(String content, String sourceName) {
        try {
            var analysis = com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer.proLeap().analyze(
                    com.aresstack.spaceharvester.mainframe.cobol.CobolSource.fixed(sourceName, content),
                    com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver.NONE);
            var procedures = analysis.controlFlow().procedures();
            return procedures.isEmpty() ? null : CobolControlFlowDiagram.render(procedures.getFirst());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "COBOL-Kontrollfluss gescheitert", e);
            return null;
        }
    }

    private static List<com.aresstack.spaceharvester.mainframe.calltree.ExternalCall> cobolCalls(JclOutlineModel model) {
        java.util.Map<String, com.aresstack.spaceharvester.mainframe.calltree.ExternalCall> calls =
                new java.util.LinkedHashMap<>();
        for (JclElement element : model.getElements()) {
            if (element.getType() == JclElementType.CALL_STMT) {
                String target = element.getParameter("TARGET");
                if (target != null && !target.isBlank()) {
                    calls.putIfAbsent(target.toUpperCase(Locale.ROOT),
                            new com.aresstack.spaceharvester.mainframe.calltree.ExternalCall(
                                    target, "CALL", element.getLineNumber(), element.getRawText()));
                }
            }
        }
        return List.copyOf(calls.values());
    }

    /** DDMs aus den VIEW … OF …-Anweisungen eines Natural-Programms (MainframeMate: aus dem NDV-Quellen-Cache). */
    static List<DdmParser.DdmDefinition> resolveDdms(JclOutlineModel model, SourceResolver resolver) {
        List<DdmParser.DdmDefinition> result = new ArrayList<>();
        if (resolver == null) {
            return result;
        }
        Set<String> names = new LinkedHashSet<>();
        for (JclElement element : model.getElements()) {
            if (element.getType() == JclElementType.NAT_DATA_VIEW) {
                String reference = element.getParameter("OF");
                if (reference != null && !reference.isEmpty()) {
                    names.add(reference.toUpperCase(Locale.ROOT));
                }
            }
        }
        DdmParser parser = new DdmParser();
        for (String name : names) {
            String source;
            try {
                source = resolver.resolve(name);
            } catch (RuntimeException e) {
                source = null;
            }
            if (source != null) {
                DdmParser.DdmDefinition definition = parser.parse(source, name);
                if (definition != null) {
                    result.add(definition);
                }
            }
        }
        return result;
    }

    private static String ddmName(String sourceName) {
        String name = sourceName;
        if (name != null && name.contains(".")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        if (name != null && name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        return name;
    }
}
