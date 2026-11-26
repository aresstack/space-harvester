package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Static, source-mapped control flow. PERFORM returns require the matching active call site. */
public record CobolControlFlow(List<Procedure> procedures, List<Limitation> limitations) {
    public static final CobolControlFlow EMPTY = new CobolControlFlow(List.of(), List.of());
    public CobolControlFlow {
        procedures = List.copyOf(procedures);
        limitations = List.copyOf(limitations);
    }
    public boolean partial() { return !limitations.isEmpty(); }

    public enum NodeKind { ENTRY, EXIT, LABEL, BOUNDARY, STATEMENT, DECISION, CALL, PERFORM, UNKNOWN }
    public enum EdgeKind {
        NEXT, TRUE, FALSE, WHEN, OTHERWISE, LOOP_BODY, LOOP_EXIT, GOTO, DEPENDING_TARGET,
        NEXT_SENTENCE, TERMINATE, CALL_RETURN, PERFORM_CALL, PERFORM_RETURN, FALLTHROUGH, UNKNOWN
    }

    public record Node(String id, NodeKind kind, String label, CobolAnalysis.Span span, int syntaxIndex) {
        public Node(String id, NodeKind kind, String label, CobolAnalysis.Span span) {
            this(id, kind, label, span, -1);
        }
    }
    public record Edge(String from, String to, EdgeKind kind, String condition, String callSite) { }
    public record Limitation(String message, CobolAnalysis.Position position) { }
    public record Procedure(String name, String entry, String exit, List<Node> nodes, List<Edge> edges) {
        public Procedure {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            Set<String> ids = nodes.stream().map(Node::id).collect(Collectors.toSet());
            if (ids.size() != nodes.size() || !ids.contains(entry) || !ids.contains(exit)
                    || edges.stream().anyMatch(edge -> !ids.contains(edge.from()) || !ids.contains(edge.to()))) {
                throw new IllegalArgumentException("Invalid control flow graph");
            }
        }
    }
}
