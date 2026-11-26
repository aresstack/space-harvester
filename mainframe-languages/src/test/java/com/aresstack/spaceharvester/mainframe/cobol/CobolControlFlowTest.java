package com.aresstack.spaceharvester.mainframe.cobol;

import com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow.*;
import com.aresstack.spaceharvester.mainframe.diagram.CobolControlFlowDiagram;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CobolControlFlowTest {
    @Test
    void branchesJoinOnlyAtTheRealContinuationAndStopHasNoFallthrough() {
        var graph = graph("""
                IF X = 1
                    MOVE 2 TO X
                ELSE
                    MOVE 3 TO X
                END-IF
                DISPLAY X
                STOP RUN.
                NEVER-ENTERED.
                    DISPLAY 'UNREACHABLE'.
                """);
        var decision = node(graph, "IF X = 1");
        var yes = node(graph, "MOVE 2 TO X");
        var no = node(graph, "MOVE 3 TO X");
        var join = node(graph, "DISPLAY X");
        assertEdge(graph, decision, yes, EdgeKind.TRUE);
        assertEdge(graph, decision, no, EdgeKind.FALSE);
        assertEdge(graph, yes, join, EdgeKind.NEXT);
        assertEdge(graph, no, join, EdgeKind.NEXT);
        var stop = node(graph, "STOP RUN");
        assertThat(outgoing(graph, stop)).singleElement().satisfies(edge -> {
            assertThat(edge.kind()).isEqualTo(EdgeKind.TERMINATE);
            assertThat(edge.to()).isEqualTo(graph.exit());
        });
        assertThat(decision.span().start().source()).isEqualTo("DEMO");
        assertThat(decision.span().start().line()).isEqualTo(7);
    }

    @Test
    void evaluatesAlternativesAndNextSentenceWithoutLexicalFallthrough() {
        var graph = graph("""
                EVALUATE X
                    WHEN 1 WHEN 2 MOVE 4 TO X
                    WHEN 3 MOVE 5 TO X
                    WHEN OTHER MOVE 6 TO X
                END-EVALUATE
                IF X = 4 NEXT SENTENCE END-IF
                DISPLAY 'SKIP'.
                DISPLAY 'NEXT'.
                GOBACK.
                """);
        var evaluate = node(graph, "EVALUATE X");
        assertThat(outgoing(graph, evaluate)).extracting(Edge::kind)
                .containsExactly(EdgeKind.WHEN, EdgeKind.WHEN, EdgeKind.OTHERWISE);
        assertThat(outgoing(graph, evaluate).getFirst().condition()).contains("WHEN 1", "WHEN 2");
        var branch = node(graph, "IF X = 4");
        assertEdge(graph, branch, node(graph, "DISPLAY 'NEXT'"), EdgeKind.TRUE);
        assertEdge(graph, branch, node(graph, "DISPLAY 'SKIP'"), EdgeKind.FALSE);
        for (String statement : List.of("MOVE 4 TO X", "MOVE 5 TO X", "MOVE 6 TO X")) {
            assertEdge(graph, node(graph, statement), branch, EdgeKind.NEXT);
        }
    }

    @Test
    void modelsPreAndPostTestLoopsWithActualBackEdges() {
        var graph = graph("""
                PERFORM UNTIL X = 1
                    ADD 1 TO X
                END-PERFORM
                PERFORM WITH TEST AFTER UNTIL X = 0
                    SUBTRACT 1 FROM X
                END-PERFORM
                GOBACK.
                """);
        var before = node(graph, "UNTIL X = 1");
        var after = node(graph, "WITH TEST AFTER UNTIL X = 0");
        var add = node(graph, "ADD 1 TO X");
        var subtract = node(graph, "SUBTRACT 1 FROM X");
        assertEdge(graph, before, add, EdgeKind.LOOP_BODY);
        assertEdge(graph, add, before, EdgeKind.NEXT);
        assertEdge(graph, after, subtract, EdgeKind.LOOP_BODY);
        assertEdge(graph, subtract, after, EdgeKind.NEXT);
        assertEdge(graph, after, node(graph, "GOBACK"), EdgeKind.LOOP_EXIT);
        var postEntry = graph.nodes().stream().filter(n -> n.kind() == NodeKind.PERFORM && n.span().start().line() == 10)
                .findFirst().orElseThrow();
        assertEdge(graph, postEntry, subtract, EdgeKind.NEXT);
    }

    @Test
    void distinguishesPerformReturnFromOrdinaryParagraphFallthrough() {
        var graph = graph("""
                MAIN-PARA.
                    PERFORM FIRST-PARA THRU LAST-PARA.
                    GOBACK.
                FIRST-PARA.
                    MOVE 1 TO X.
                LAST-PARA.
                    DISPLAY X.
                AFTER-PARA.
                    STOP RUN.
                """);
        var call = graph.edges().stream().filter(e -> e.kind() == EdgeKind.PERFORM_CALL).findFirst().orElseThrow();
        assertThat(call.to()).isEqualTo(node(graph, "FIRST-PARA").id());
        var ret = graph.edges().stream().filter(e -> e.kind() == EdgeKind.PERFORM_RETURN).findFirst().orElseThrow();
        assertThat(ret.callSite()).isEqualTo(call.callSite()).isEqualTo(call.from());
        assertThat(ret.from()).isEqualTo(node(graph, "End of LAST-PARA").id());
        assertThat(ret.to()).isEqualTo(node(graph, "GOBACK").id());
        assertEdge(graph, node(graph, "End of LAST-PARA"), node(graph, "AFTER-PARA"), EdgeKind.FALLTHROUGH);
        assertThat(outgoing(graph, node(graph, "End of LAST-PARA"))).hasSize(2);
        assertThat(CobolControlFlowDiagram.render(graph)).contains("PERFORM_CALL", "PERFORM_RETURN", "FALLTHROUGH");
    }

    @Test
    void computedGotoHasExplicitCandidatesAndOutOfRangeContinuation() {
        var graph = graph("""
                GO TO FIRST-PARA LAST-PARA DEPENDING ON X
                GOBACK.
                FIRST-PARA.
                    STOP RUN.
                LAST-PARA.
                    GO TO FIRST-PARA.
                """);
        var jump = node(graph, "GO TO FIRST-PARA LAST-PARA DEPENDING ON X");
        assertEdge(graph, jump, node(graph, "FIRST-PARA"), EdgeKind.DEPENDING_TARGET);
        assertEdge(graph, jump, node(graph, "LAST-PARA"), EdgeKind.DEPENDING_TARGET);
        assertEdge(graph, jump, node(graph, "GOBACK"), EdgeKind.OTHERWISE);
        assertThat(outgoing(graph, node(graph, "GO TO FIRST-PARA"))).singleElement()
                .satisfies(edge -> assertThat(edge.kind()).isEqualTo(EdgeKind.GOTO));
    }

    @Test
    void unsupportedTransfersAndMissingCopybooksAreExplicitRatherThanInvented() {
        var analysis = analyze("GO TO UNKNOWN-PARA.\nGOBACK.");
        assertThat(analysis.controlFlow().partial()).isTrue();
        var graph = analysis.controlFlow().procedures().getFirst();
        assertThat(outgoing(graph, node(graph, "GO TO UNKNOWN-PARA"))).singleElement()
                .satisfies(edge -> assertThat(edge.kind()).isEqualTo(EdgeKind.UNKNOWN));
        var missing = analyze("COPY MISSING.\nGOBACK.");
        assertThat(missing.controlFlow().partial()).isTrue();
        assertThat(missing.controlFlow().procedures()).isEmpty();
    }

    @Test
    void retainsCopybookProvenanceInControlFlow() {
        var result = CobolAnalyzer.proLeap().analyze(CobolSource.fixed("DEMO", source("COPY LOGIC.\nGOBACK.")),
                (including, name, library) -> Optional.of(CobolSource.fixed("LOGIC", "           MOVE 2 TO X.\n")));
        var graph = result.controlFlow().procedures().getFirst();
        var move = node(graph, "MOVE 2 TO X");
        assertThat(move.span().start().source()).isEqualTo("LOGIC");
        assertThat(move.span().start().line()).isEqualTo(1);
    }

    private static CobolAnalysis analyze(String body) {
        return CobolAnalyzer.proLeap().analyze(CobolSource.fixed("DEMO", source(body)), CopybookResolver.NONE);
    }

    private static Procedure graph(String body) {
        var result = analyze(body);
        assertThat(result.controlFlow().procedures()).as("diagnostics: %s; limitations: %s",
                result.diagnostics(), result.controlFlow().limitations()).hasSize(1);
        return result.controlFlow().procedures().getFirst();
    }

    private static String source(String body) {
        return "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. DEMO.\n"
                + "       DATA DIVISION.\n       WORKING-STORAGE SECTION.\n       01 X PIC 9.\n"
                + "       PROCEDURE DIVISION.\n" + body.lines().map(line -> "       " + line)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
    }

    private static Node node(Procedure graph, String label) {
        return graph.nodes().stream().filter(node -> node.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + label + " in " + graph.nodes()));
    }

    private static List<Edge> outgoing(Procedure graph, Node node) {
        return graph.edges().stream().filter(edge -> edge.from().equals(node.id())).toList();
    }

    private static void assertEdge(Procedure graph, Node from, Node to, EdgeKind kind) {
        assertThat(outgoing(graph, from)).anyMatch(edge -> edge.to().equals(to.id()) && edge.kind() == kind);
    }
}
