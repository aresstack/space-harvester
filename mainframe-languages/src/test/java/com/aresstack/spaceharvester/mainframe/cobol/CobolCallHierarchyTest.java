package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.aresstack.spaceharvester.mainframe.cobol.CobolSearch.Kind.*;
import static com.aresstack.spaceharvester.mainframe.cobol.CobolSearch.Mode.CALLEES_HIERARCHY;
import static com.aresstack.spaceharvester.mainframe.cobol.CobolSearch.Mode.CALLERS_HIERARCHY;
import static org.assertj.core.api.Assertions.assertThat;

class CobolCallHierarchyTest {
    @Test
    void connectsLiteralCallsFromActualParserResults() {
        List<CobolSearch.Fact> facts = new java.util.ArrayList<>();
        facts.addAll(parse("A", "B"));
        facts.addAll(parse("B", "C"));
        facts.addAll(parse("C", "A"));
        List<CobolSearch.Fact> rows = new CobolSearch(facts).search(CALLEES_HIERARCHY, "A", 20, 0).hits();
        assertThat(rows).extracting(CobolSearch.Fact::name).containsExactly("A", "B", "C", "A");
        assertThat(rows.get(3).attributes()).containsEntry("ALREADY_VISITED", "true");
    }

    private static List<CobolSearch.Fact> parse(String name, String target) {
        String member = "LIB(" + name + ")";
        String source = "       IDENTIFICATION DIVISION.\n"
                + "       PROGRAM-ID. " + name + ".\n"
                + "       PROCEDURE DIVISION.\n"
                + "           CALL '" + target + "'.\n"
                + "           GOBACK.\n";
        CobolAnalysis analysis = CobolAnalyzer.proLeap().analyze(CobolSource.fixed(member, source),
                (including, copybook, library) -> java.util.Optional.empty());
        assertThat(analysis.diagnostics()).isEmpty();
        return CobolSearch.programFacts(member, "r", analysis);
    }

    @Test
    void traversesBothDirectionsAndTerminatesCyclesWithoutLosingCallSites() {
        CobolSearch search = search(
                program("A", "LIB(A)"), program("B", "LIB(B)"), program("C", "LIB(C)"),
                call("B", "LIB(A)", 10), call("B", "LIB(A)", 11),
                call("C", "LIB(B)", 20), call("A", "LIB(C)", 30));
        List<CobolSearch.Fact> outgoing = search.search(CALLEES_HIERARCHY, "a", 20, 0).hits();
        assertThat(outgoing).extracting(CobolSearch.Fact::line).containsExactly(1, 10, 11, 20, 30);
        assertThat(outgoing).extracting(f -> f.attributes().get("HIERARCHY_DEPTH"))
                .containsExactly("0", "1", "1", "2", "3");
        assertThat(outgoing.get(4).attributes()).containsEntry("ALREADY_VISITED", "true");
        assertThat(outgoing.get(1).revision()).isEqualTo("rev-LIB(A)");
        assertThat(search.search(CALLERS_HIERARCHY, "A", 20, 0).hits())
                .extracting(CobolSearch.Fact::line).containsExactly(1, 30, 20, 10, 11);
        assertThat(search.search(CALLEES_HIERARCHY, "A", 2, 2).hits())
                .extracting(CobolSearch.Fact::line).containsExactly(11, 20);
        assertThat(search.search(CALLEES_HIERARCHY, "A", 2, 2).total()).isEqualTo(5);
    }

    @Test
    void reportsAmbiguousRootsAndNeverTraversesAmbiguousOrDynamicTargets() {
        CobolSearch search = search(
                program("A", "LIB(A)"), program("B", "LIB1(B)"), program("B", "LIB2(B)"),
                program("C", "LIB(C)"), call("B", "LIB(A)", 10),
                call("MISSING", "LIB(A)", 11),
                fact(DYNAMIC_CALL, "WS-TARGET", "LIB(A)", 12),
                call("C", "LIB1(B)", 20));
        List<CobolSearch.Fact> roots = search.search(CALLEES_HIERARCHY, "B", 20, 0).hits();
        assertThat(roots).hasSize(2).allSatisfy(f ->
                assertThat(f.attributes()).containsEntry("HIERARCHY_STATUS", "SELECT_ROOT"));
        String selector = roots.get(0).attributes().get("ROOT_SELECTOR");
        assertThat(search.search(CALLEES_HIERARCHY, selector, 20, 0).hits())
                .extracting(CobolSearch.Fact::line).containsExactly(1, 20);
        List<CobolSearch.Fact> outgoing = search.search(CALLEES_HIERARCHY, "A", 20, 0).hits();
        assertThat(outgoing).extracting(f -> f.attributes().get("HIERARCHY_STATUS"))
                .containsExactly("ROOT", "AMBIGUOUS_TARGET", "TARGET_NOT_IN_SNAPSHOT", "DYNAMIC_TARGET");
        assertThat(outgoing.get(1).attributes().get("TARGET_CANDIDATES"))
                .contains("LIB1(B)#B@1", "LIB2(B)#B@1");
        assertThat(search.search(CALLERS_HIERARCHY, selector, 20, 0).hits())
                .extracting(f -> f.attributes().get("HIERARCHY_STATUS"))
                .containsExactly("ROOT", "AMBIGUOUS_TARGET");
    }

    @Test
    void distinguishesLeavesMissingRootsAndAmbiguousCallerOwnership() {
        CobolSearch search = search(program("A", "LIB(A)"), program("NESTED", "LIB(A)"),
                program("B", "LIB(B)"), call("B", "LIB(A)", 10));
        assertThat(search.search(CALLEES_HIERARCHY, "B", 20, 0).hits()).singleElement()
                .satisfies(f -> assertThat(f.attributes()).containsEntry("HIERARCHY_STATUS", "ROOT"));
        assertThat(search.search(CALLEES_HIERARCHY, "UNKNOWN", 20, 0).total()).isZero();
        assertThat(search.search(CALLERS_HIERARCHY, "B", 20, 0).hits())
                .extracting(f -> f.attributes().get("HIERARCHY_STATUS"))
                .containsExactly("ROOT", "AMBIGUOUS_CALLER");
        assertThat(search.search(CALLEES_HIERARCHY, "A", 20, 0).hits())
                .extracting(f -> f.attributes().get("HIERARCHY_STATUS"))
                .containsExactly("ROOT", "AMBIGUOUS_CALLER");
    }

    private static CobolSearch search(CobolSearch.Fact... facts) {
        return new CobolSearch(Arrays.asList(facts));
    }

    private static CobolSearch.Fact program(String name, String member) {
        return fact(PROGRAM, name, member, 1);
    }

    private static CobolSearch.Fact call(String name, String member, int line) {
        return fact(CALL, name, member, line);
    }

    private static CobolSearch.Fact fact(CobolSearch.Kind kind, String name, String member, int line) {
        return new CobolSearch.Fact(kind, name, member, member, member, line, "rev-" + member,
                Collections.emptyMap(), false);
    }
}
