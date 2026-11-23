package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.deck.ndv.analysis.NaturalDependencyGraph;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis;
import com.aresstack.spaceharvester.mainframe.dependency.CallNode;
import com.aresstack.spaceharvester.mainframe.dependency.NaturalRelations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter: die Natural-Beziehungen des Editors (Aufrufhierarchie, DDM-Benutzer) aus dem Abhängigkeitsgraphen des
 * NDV-Bereichs, wie {@code NaturalAnalysisService.getCallHierarchy} in MainframeMate. Gefragt werden die Analysen der
 * offenen NDV-Sitzungen, die gewählte zuerst; die erste mit gebautem Graphen der Bibliothek antwortet.
 */
final class NdvNaturalRelations implements NaturalRelations {

    private final Supplier<List<NdvAnalysis>> analyses;

    NdvNaturalRelations(Supplier<List<NdvAnalysis>> analyses) {
        this.analyses = analyses;
    }

    @Override
    public Optional<CallNode> callees(String library, String object, int depth) {
        return hierarchy(library, object, true, depth);
    }

    @Override
    public Optional<CallNode> callers(String library, String object, int depth) {
        return hierarchy(library, object, false, depth);
    }

    private Optional<CallNode> hierarchy(String library, String object, boolean callees, int depth) {
        if (library == null || object == null || library.isBlank() || object.isBlank()) {
            return Optional.empty();
        }
        String lib = library.trim().toUpperCase(Locale.ROOT);
        for (NdvAnalysis analysis : analyses.get()) {
            Optional<NaturalDependencyGraph> graph = analysis.graph(lib);
            if (graph.isPresent()) {
                NaturalDependencyGraph.CallHierarchyNode root =
                        graph.get().getCallHierarchy(object.trim(), callees, Math.max(1, depth));
                return Optional.of(convert(lib, root, callees, null));
            }
        }
        return Optional.empty();
    }

    /**
     * Ein Knoten mit Ziel {@code ndv://LIB/OBJEKT}. Die Zeile steht bei Aufgerufenen im Aufrufer (dem Elternknoten),
     * bei Aufrufern im Aufrufer selbst.
     */
    private static CallNode convert(String library, NaturalDependencyGraph.CallHierarchyNode node, boolean callees,
                                    String parent) {
        List<CallNode> children = new ArrayList<>();
        for (NaturalDependencyGraph.CallHierarchyNode child : node.getChildren()) {
            children.add(convert(library, child, callees, node.getObjectName()));
        }
        String target = target(library, node.getObjectName());
        String sourceFile = node.getLineNumber() <= 0 ? null
                : callees ? (parent == null ? null : target(library, parent)) : target;
        String kind = node.getReferenceKind() == null ? null : node.getReferenceKind().getCode();
        return new CallNode(node.getDisplayText(), target, node.isRecursive(), Math.max(0, node.getLineNumber()),
                sourceFile, kind, children);
    }

    static String target(String library, String object) {
        return "ndv://" + library + "/" + object.toUpperCase(Locale.ROOT);
    }
}
