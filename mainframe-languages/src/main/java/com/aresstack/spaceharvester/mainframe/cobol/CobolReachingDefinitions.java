package com.aresstack.spaceharvester.mainframe.cobol;
import com.aresstack.spaceharvester.mainframe.cobol.CobolDataFlow.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Walk modeled predecessor paths to find possible earlier writes, including loop-carried definitions. */
public final class CobolReachingDefinitions {
    private CobolReachingDefinitions() { }

    public static CobolDataFlow analyze(CobolDataFlow flow, CobolControlFlow control) {
        var symbols = flow.symbols().stream().collect(Collectors.toMap(Symbol::id, symbol -> symbol));
        var byNode = flow.operations().stream().filter(operation -> !operation.controlNode().isEmpty())
                .collect(Collectors.groupingBy(Operation::controlNode));
        var reads = new ArrayList<ReadSite>();
        var limitations = new ArrayList<>(flow.limitations());
        if (control.partial()) {
            limitations.add(new Limitation("Preceding definitions cover modeled control paths only; unknown transitions are not expanded",
                    CobolAnalysis.Position.UNKNOWN));
        }
        if (flow.operations().stream().anyMatch(operation -> operation.controlNode().isEmpty())) {
            limitations.add(new Limitation("Some data operations have no unique control-flow node",
                    CobolAnalysis.Position.UNKNOWN));
        }
        for (var graph : control.procedures()) {
            var paths = new Paths(graph, byNode, symbols);
            if (graph.edges().stream().anyMatch(edge -> edge.kind() == CobolControlFlow.EdgeKind.PERFORM_RETURN)) {
                limitations.add(new Limitation("PERFORM return paths are not expanded by call stack; candidates may include infeasible paths",
                        paths.nodes.get(graph.entry()).span().start()));
            }
            paths.collect(reads);
        }
        return new CobolDataFlow(flow.symbols(), flow.operations(), limitations, reads);
    }

    /** Expand a single read's candidates without reparsing or materializing a quadratic all-pairs relation. */
    public static List<Definition> candidates(CobolAnalysis analysis, ReadSite read) {
        var flow = analysis.dataFlow();
        if (!flow.readSites().contains(read)) return List.of();
        var symbols = flow.symbols().stream().collect(Collectors.toMap(Symbol::id, symbol -> symbol));
        var operations = flow.operations().stream().filter(operation -> !operation.controlNode().isEmpty())
                .collect(Collectors.groupingBy(Operation::controlNode));
        for (var graph : analysis.controlFlow().procedures()) {
            if (graph.nodes().stream().noneMatch(node -> node.id().equals(read.controlNode()))) continue;
            return new Paths(graph, operations, symbols).priorWrites(new Paths.Query(read.controlNode(), read.operand().symbolId()));
        }
        return List.of();
    }

    private static final class Paths {
        private record Query(String node, String symbol) { }

        private final CobolControlFlow.Procedure graph;
        private final Map<String, List<Operation>> operations;
        private final Map<String, Symbol> symbols;
        private final Map<String, CobolControlFlow.Node> nodes;
        private final Map<String, List<String>> predecessors = new HashMap<>();
        private final Set<String> reachable;


        Paths(CobolControlFlow.Procedure graph, Map<String, List<Operation>> operations, Map<String, Symbol> symbols) {
            this.graph = graph;
            this.operations = operations;
            this.symbols = symbols;
            nodes = graph.nodes().stream().collect(Collectors.toMap(CobolControlFlow.Node::id, node -> node));
            var successors = new HashMap<String, List<String>>();
            for (var edge : graph.edges()) {
                successors.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
                predecessors.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
            }
            reachable = reachable(graph.entry(), successors);
        }

        void collect(List<ReadSite> result) {
            for (var node : graph.nodes()) {
                if (!reachable.contains(node.id())) continue;
                for (var operation : operations.getOrDefault(node.id(), List.of())) {
                    for (var access : operation.accesses()) {
                        var symbol = symbols.get(access.operand().symbolId());
                        if (!isRead(access.kind()) || symbol == null || symbol.complexStorage()) continue;
                        result.add(new ReadSite(operation.program(), node.id(), access.operand()));
                    }
                }
            }
        }

        private List<Definition> priorWrites(Query query) {
            // Visit each predecessor at most once per query, including back edges.
            var pending = new ArrayDeque<>(predecessors.getOrDefault(query.node(), List.of()));
            Set<String> visited = new HashSet<>();
            Set<Definition> candidates = new LinkedHashSet<>();
            while (!pending.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                String node = pending.removeFirst();
                if (!reachable.contains(node) || !visited.add(node)) continue;
                if (node.equals(graph.entry())) {
                    candidates.add(new Definition(DefinitionKind.ENTRY_STATE, node, nodes.get(node).span()));
                    continue;
                }
                boolean overwritten = false;
                for (var operation : operations.getOrDefault(node, List.of())) {
                    if (operation.incomplete()) {
                        candidates.add(new Definition(DefinitionKind.UNKNOWN_EFFECT, node, operation.span()));
                    }
                    for (var access : operation.accesses()) {
                        if (!query.symbol().equals(access.operand().symbolId())) continue;
                        if (access.kind() == AccessKind.WRITE || access.kind() == AccessKind.READ_WRITE) {
                            candidates.add(new Definition(DefinitionKind.WRITE, node, access.operand().span()));
                            overwritten |= fullWrite(operation, access);
                        } else if (access.kind() == AccessKind.POSSIBLE_READ_WRITE || access.kind() == AccessKind.UNKNOWN) {
                            candidates.add(new Definition(DefinitionKind.POSSIBLE_WRITE, node, access.operand().span()));
                        }
                    }
                }
                if (!overwritten) pending.addAll(predecessors.getOrDefault(node, List.of()));
            }
            return List.copyOf(candidates);
        }

        private static boolean fullWrite(Operation operation, Access access) {
            // Use only complete, single-field stores without explicit size-error handling.
            String verb = operation.verb().toUpperCase(java.util.Locale.ROOT);
            return Set.of("MOVE", "ADD", "SUBTRACT", "MULTIPLY").contains(verb) && !operation.incomplete()
                    && (access.kind() == AccessKind.WRITE || access.kind() == AccessKind.READ_WRITE)
                    && operation.transfers().size() == 1
                    && operation.accesses().stream().allMatch(item -> item.operand().kind() == OperandKind.SYMBOL
                            && (item.kind() == AccessKind.READ || item.kind() == AccessKind.WRITE
                            || item.kind() == AccessKind.READ_WRITE))
                    && operation.transfers().getFirst().inputs().stream().noneMatch(input -> input.kind() == OperandKind.UNKNOWN);
        }

        private static Set<String> reachable(String entry, Map<String, List<String>> successors) {
            var visited = new HashSet<String>();
            var pending = new ArrayDeque<String>();
            pending.add(entry);
            while (!pending.isEmpty()) {
                String node = pending.removeFirst();
                if (visited.add(node)) pending.addAll(successors.getOrDefault(node, List.of()));
            }
            return visited;
        }

        private static boolean isRead(AccessKind kind) {
            return kind == AccessKind.READ || kind == AccessKind.READ_WRITE || kind == AccessKind.ADDRESS_READ
                    || kind == AccessKind.POSSIBLE_READ_WRITE;
        }
    }
}
