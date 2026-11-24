package com.aresstack.spaceharvester.mainframe.dependency;

import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import com.aresstack.spaceharvester.mainframe.parser.DdmOutlineBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Beziehungen eines offenen Quelltexts: „Ressourcen“ (Daten, Data Areas, Datasets) und die Aufrufhierarchie
 * („Callees“/„Caller“). Übernommen aus {@code TabbedPaneManager.updateLeftDrawerRelations} und den
 * {@code show…InLeftDrawer}-Methoden von MainframeMate, ohne Swing: Die Oberfläche zeigt nur noch an.
 *
 * <ul>
 *   <li>JCL: Ressourcen = INCLUDE, JCLLIB, DSN; Callees = JOB → EXEC → DD mit Natural-Programmen
 *       ({@code nat-jcl://}) und Systemfunktionen ({@code sysfunc://}).</li>
 *   <li>Natural: Ressourcen = USING, INCLUDE, MAP, VIEW, DB-Zugriff aus dem Quelltext; Callees/Caller aus dem Graphen
 *       der Bibliothek über {@link NaturalRelations}.</li>
 *   <li>DDM: Programme, die das DDM benutzen, über {@link NaturalRelations#callers}.</li>
 *   <li>COBOL: externe Aufrufe über {@link CobolCalls}.</li>
 * </ul>
 *
 * <p>Darf blockieren (Graph vom NDV-Server) und läuft nie auf dem EDT.
 */
public final class SourceRelations {

    /** Tiefe der Natural-Hierarchie wie in MainframeMate ({@code getCallHierarchy(…, 5)}). */
    public static final int NATURAL_DEPTH = 5;
    /** Tiefe der DDM-Hierarchie wie {@code buildDdmHierarchy(ddmName, lib, 3)}. */
    public static final int DDM_DEPTH = 3;

    /** Inhalt der Ansicht „Ressourcen“; ohne Einträge steht {@code placeholder} da. */
    public record Resources(String title, Map<String, List<Relation>> sections, int total, String placeholder) {

        public Resources {
            sections = sections == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sections));
        }

        static Resources empty(String placeholder) {
            return new Resources("", Map.of(), 0, placeholder);
        }
    }

    /** Ein Hierarchie-Reiter; ohne Wurzel steht {@code placeholder} da. */
    public record Hierarchy(Optional<CallNode> root, String objectName, String placeholder) {

        static Hierarchy of(CallNode root, String objectName) {
            return new Hierarchy(Optional.of(root), objectName, null);
        }

        static Hierarchy empty(String placeholder) {
            return new Hierarchy(Optional.empty(), null, placeholder);
        }
    }

    /** Ergebnis für ein Dokument. */
    public record Analysis(Resources resources, Hierarchy callees, Hierarchy callers) {

        public static final Analysis NONE = new Analysis(Resources.empty(""), Hierarchy.empty(""),
                Hierarchy.empty(""));
    }

    private static final Pattern PGM = Pattern.compile("PGM=([A-Z0-9$#@]+)", Pattern.CASE_INSENSITIVE);

    private final JclDependencyService jcl = new JclDependencyService();
    private final NaturalDependencyService natural = new NaturalDependencyService();
    private final NaturalRelations naturalRelations;
    private final CobolCalls cobolCalls;
    private final Predicate<String> systemFunction;

    /**
     * @param systemFunction ob ein PGM-Name eine bekannte Systemfunktion ist (Einstellungen › Diagramme ›
     *                       Systemfunktionen); dann verweist die Hierarchie auf Wikipedia
     */
    public SourceRelations(NaturalRelations naturalRelations, CobolCalls cobolCalls, Predicate<String> systemFunction) {
        this.naturalRelations = Objects.requireNonNull(naturalRelations, "naturalRelations");
        this.cobolCalls = Objects.requireNonNull(cobolCalls, "cobolCalls");
        this.systemFunction = Objects.requireNonNull(systemFunction, "systemFunction");
    }

    /**
     * @param language wie der Text geparst wird (Sprachwahl im Editor); leer = keine Beziehungen
     * @param content  der Text
     * @param name     Dateiname bzw. NDV-Pfad {@code LIB/NAME.NSP}
     * @param library  Natural-Bibliothek des Dokuments, {@code null} wenn es keine NDV-Quelle ist
     */
    public Analysis analyze(Optional<SourceLanguage> language, String content, String name, String library) {
        if (language.isEmpty() || content == null) {
            return Analysis.NONE;
        }
        return switch (language.get()) {
            case JCL -> jcl(content, name);
            case NATURAL -> natural(content, name, library);
            case DDM -> ddm(name, library);
            case COBOL -> cobol(content, name);
        };
    }

    // ---- JCL (showJclDependenciesInLeftDrawer, showJclCallHierarchy, convertJclCallNode) ---------------------------

    private Analysis jcl(String content, String name) {
        JclDependencyService.JclDependencyResult result = jcl.analyze(content, name);
        Map<String, List<Relation>> sections = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<JclDependencyService.JclDependencyKind, List<JclDependencyService.JclDependency>> group
                : result.getGrouped().entrySet()) {
            JclDependencyService.JclDependencyKind kind = group.getKey();
            if (isJclCallKind(kind)) {
                continue; // steht in den Callees, nicht in den Daten
            }
            List<Relation> entries = new ArrayList<>();
            for (JclDependencyService.JclDependency dep : group.getValue()) {
                entries.add(new Relation(dep.getDisplayText(), null, "JCL_DEP_" + kind.getCode(),
                        dep.getLineNumber()));
            }
            sections.put(kind.getDisplayLabel(), entries);
            total += entries.size();
        }
        Resources resources = total == 0 ? Resources.empty("Keine JCL-Daten gefunden.")
                : new Resources("JCL-Daten", sections, total, null);

        List<JclDependencyService.JclCallNode> roots = jcl.buildCallHierarchy(content, name);
        Hierarchy callees;
        if (roots.isEmpty()) {
            callees = Hierarchy.empty("Keine JCL-Ausführungshierarchie gefunden.");
        } else {
            List<CallNode> children = new ArrayList<>();
            for (JclDependencyService.JclCallNode root : roots) {
                children.add(convert(root));
            }
            callees = Hierarchy.of(CallNode.root("JCL Ausführung", children), fileName(name));
        }
        return new Analysis(resources, callees, Hierarchy.empty("(keine eingehenden Aufrufe)"));
    }

    private static boolean isJclCallKind(JclDependencyService.JclDependencyKind kind) {
        return kind == JclDependencyService.JclDependencyKind.PROGRAM
                || kind == JclDependencyService.JclDependencyKind.PROCEDURE
                || kind == JclDependencyService.JclDependencyKind.NATURAL_PROGRAM;
    }

    private CallNode convert(JclDependencyService.JclCallNode node) {
        List<CallNode> children = new ArrayList<>();
        for (JclDependencyService.JclCallNode child : node.getChildren()) {
            children.add(convert(child));
        }
        String target = null;
        String natRef = node.getNaturalRef();
        if (natRef != null && natRef.contains(";")) {
            String[] parts = natRef.split(";", 2);
            target = "nat-jcl://" + parts[0] + "/" + parts[1];
        }
        if (target == null && node.getDisplayText() != null) {
            Matcher pgm = PGM.matcher(node.getDisplayText());
            if (pgm.find()) {
                String program = pgm.group(1).toUpperCase(Locale.ROOT);
                if (systemFunction.test(program)) {
                    target = "sysfunc://" + program;
                }
            }
        }
        return new CallNode(node.getDisplayText(), target, false, node.getLineNumber(), null, null, children);
    }

    // ---- Natural (showFullDependenciesInLeftDrawer, populateCallHierarchy) -----------------------------------------

    private Analysis natural(String content, String name, String library) {
        NaturalDependencyService.DependencyResult result = natural.analyze(content, name);
        Map<String, List<Relation>> sections = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<NaturalDependencyService.DependencyKind, List<NaturalDependencyService.Dependency>> group
                : result.getGrouped().entrySet()) {
            NaturalDependencyService.DependencyKind kind = group.getKey();
            if (isNaturalCallKind(kind)) {
                continue; // steht in den Callees, nicht in den Daten
            }
            List<Relation> entries = new ArrayList<>();
            for (NaturalDependencyService.Dependency dep : group.getValue()) {
                entries.add(new Relation(dep.getDisplayText(), naturalTarget(dep, library),
                        "DEPENDENCY_" + kind.getCode(), dep.getLineNumber()));
            }
            sections.put(kind.getDisplayLabel(), entries);
            total += entries.size();
        }
        Resources resources = total == 0 ? Resources.empty("Keine Daten / Data Areas.")
                : new Resources("Daten", sections, total, null);

        if (library == null || library.isBlank()) {
            return new Analysis(resources, Hierarchy.empty("Bibliothek unbekannt — kein Call-Graph."),
                    Hierarchy.empty("Bibliothek unbekannt — kein Call-Graph."));
        }
        String object = objectName(name);
        Optional<CallNode> callees = naturalRelations.callees(library, object, NATURAL_DEPTH);
        Optional<CallNode> callers = naturalRelations.callers(library, object, NATURAL_DEPTH);
        String notBuilt = "Graph wird beim Öffnen der Bibliothek erstellt.\n"
                + "Öffnen Sie den NDV-Browser und navigieren Sie zur Bibliothek.";
        return new Analysis(resources,
                callees.map(root -> Hierarchy.of(root, object)).orElse(Hierarchy.empty(notBuilt)),
                callers.map(root -> Hierarchy.of(root, object)).orElse(Hierarchy.empty(notBuilt)));
    }

    private static boolean isNaturalCallKind(NaturalDependencyService.DependencyKind kind) {
        return kind == NaturalDependencyService.DependencyKind.CALLNAT
                || kind == NaturalDependencyService.DependencyKind.FETCH
                || kind == NaturalDependencyService.DependencyKind.CALL
                || kind == NaturalDependencyService.DependencyKind.PERFORM;
    }

    /** Wie {@code buildDependencyTargetPath}: {@code ndv://LIB/ZIEL} für Verweise auf Objekte. */
    private static String naturalTarget(NaturalDependencyService.Dependency dep, String library) {
        if (library == null || library.isBlank()) {
            return null;
        }
        return switch (dep.getKind()) {
            case CALLNAT, FETCH, CALL, PERFORM, INCLUDE, USING, INPUT_MAP ->
                    "ndv://" + library + "/" + dep.getTargetName();
            default -> null;
        };
    }

    // ---- DDM (showDdmDependenciesInLeftDrawer, showDdmHierarchyInLeftDrawer) ---------------------------------------

    private Analysis ddm(String name, String library) {
        String ddmName = DdmOutlineBuilder.extractDdmName(name);
        if (library == null || library.isBlank()) {
            return new Analysis(Resources.empty("Bibliothek unbekannt — keine DDM-Benutzer."),
                    Hierarchy.empty("Bibliothek unbekannt — kein Call-Graph."),
                    Hierarchy.empty("Bibliothek unbekannt — kein Call-Graph."));
        }
        Optional<CallNode> users = naturalRelations.callers(library, ddmName, DDM_DEPTH);
        if (users.isEmpty()) {
            String notBuilt = "Graph wird beim Öffnen der Bibliothek erstellt.";
            return new Analysis(Resources.empty(notBuilt), Hierarchy.empty("(keine ausgehenden Aufrufe)"),
                    Hierarchy.empty(notBuilt));
        }
        Map<String, List<Relation>> sections = new LinkedHashMap<>();
        int total = 0;
        for (CallNode user : users.get().children()) {
            String section = user.kind() == null ? "Programme" : user.kind();
            sections.computeIfAbsent(section, k -> new ArrayList<>())
                    .add(new Relation(user.label(), user.target(), "DDM_USER", 0));
            total++;
        }
        Resources resources = total == 0 ? Resources.empty("Kein Programm benutzt dieses DDM.")
                : new Resources("DDM-Benutzer", sections, total, null);
        return new Analysis(resources, Hierarchy.empty("(keine ausgehenden Aufrufe)"),
                Hierarchy.of(users.get(), ddmName));
    }

    // ---- COBOL (showExternalCallsInLeftDrawer) ---------------------------------------------------------------------

    /** Project the same semantic result used by the COBOL outline, without parsing the document again. */
    public Analysis analyzeCobol(com.aresstack.spaceharvester.mainframe.cobol.CobolSource source,
            com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis analysis) {
        List<Relation> copies = new ArrayList<>();
        for (var element : analysis.elements()) {
            if (element.kind() == com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Kind.COPY_STMT) {
                int line = source.name().equals(element.span().start().source()) ? element.span().start().line() : 0;
                copies.add(new Relation(element.name(), null, "COPY", line));
            }
        }
        String diagnostic = analysis.diagnostics().isEmpty() ? null : analysis.diagnostics().getFirst().message();
        String partial = analysis.isPartial() ? " (Teilergebnis)" : "";
        Resources resources = copies.isEmpty()
                ? Resources.empty(diagnostic == null ? "Keine Copybooks." : "Analyse unvollständig: " + diagnostic)
                : new Resources("Copybooks" + partial, Map.of("COPY", copies), copies.size(), diagnostic);
        List<CallNode> children = new ArrayList<>();
        for (var call : analysis.knownLiteralCalls()) {
            int line = source.name().equals(call.span().start().source()) ? call.span().start().line() : 0;
            children.add(new CallNode("CALL " + call.target(), null, false, line, null, "CALL", List.of()));
        }
        Hierarchy callees = children.isEmpty()
                ? Hierarchy.empty(analysis.isPartial() ? "Analyse unvollständig." : "Keine externen Aufrufe.")
                : Hierarchy.of(CallNode.root("Externe Aufrufe" + partial, children), fileName(source.name()));
        return new Analysis(resources, callees, Hierarchy.empty("(keine eingehenden Aufrufe)"));
    }

    private Analysis cobol(String content, String name) {
        List<CobolCalls.Call> calls = cobolCalls.extract(content, name);
        if (calls.isEmpty()) {
            return new Analysis(Resources.empty("Keine Daten."), Hierarchy.empty("Keine externen Aufrufe."),
                    Hierarchy.empty("(keine eingehenden Aufrufe)"));
        }
        List<CallNode> children = new ArrayList<>();
        for (CobolCalls.Call call : calls) {
            children.add(new CallNode(call.type() + " " + call.target(), null, false, call.line(), null,
                    call.type(), List.of()));
        }
        return new Analysis(Resources.empty("Keine Daten."),
                Hierarchy.of(CallNode.root("Externe Aufrufe", children), fileName(name)),
                Hierarchy.empty("(keine eingehenden Aufrufe)"));
    }

    private static String fileName(String path) {
        if (path == null) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Wie {@code NaturalAnalysisService.extractObjectName}: letzter Pfadteil ohne Endung, groß. */
    public static String objectName(String path) {
        if (path == null) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = file.lastIndexOf('.');
        return (dot > 0 ? file.substring(0, dot) : file).toUpperCase(Locale.ROOT);
    }
}
