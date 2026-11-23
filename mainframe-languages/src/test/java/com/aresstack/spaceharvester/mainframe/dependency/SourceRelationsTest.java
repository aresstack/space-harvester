package com.aresstack.spaceharvester.mainframe.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Ressourcen und Aufrufhierarchie wie in der Beziehungen-Leiste von MainframeMate (erfundene Testdaten). */
class SourceRelationsTest {

    private static final String JCL = """
            //BEISPJOB JOB (ACCT),'TESTLAUF',CLASS=A
            //         JCLLIB ORDER=(TEST.PROCLIB)
            //SCHRITT1 EXEC PGM=IEFBR14
            //EINGABE  DD DSN=TEST.DATEN.EINGABE,DISP=SHR
            //SCHRITT2 EXEC PGM=NATBATCH,PARM='STACK=(LOGON TESTLIB;UMSATZ)'
            //AUSGABE  DD SYSOUT=*
            """;

    private static final String NATURAL = """
            DEFINE DATA
            LOCAL USING TESTLDA
            END-DEFINE
            CALLNAT 'UNTERPRG' #A
            PERFORM AUSGABE
            END
            """;

    private final SourceRelations relations = new SourceRelations(NaturalRelations.NONE, (c, n) -> List.of(),
            SystemFunctions.defaults());

    @Test
    void jclShowsDataAndTheExecutionHierarchyWithNaturalAndSystemFunctions() {
        SourceRelations.Analysis analysis = relations.analyze(Optional.of(SourceLanguage.JCL), JCL, "BEISPJOB.jcl",
                null);

        assertThat(analysis.resources().title()).isEqualTo("JCL-Daten");
        assertThat(analysis.resources().sections()).containsKeys("📚 JCLLIB", "📄 Datasets (DSN)");
        assertThat(analysis.resources().sections()).doesNotContainKey("▶ Programme (PGM)");

        CallNode job = analysis.callees().root().orElseThrow().children().get(0);
        assertThat(job.label()).startsWith("📋 BEISPJOB");
        List<String> targets = new ArrayList<>();
        collectTargets(job, targets);
        assertThat(targets).contains("sysfunc://IEFBR14", "nat-jcl://TESTLIB/UMSATZ");
    }

    @Test
    void naturalShowsDataAreasAndAsksTheGraphForTheHierarchy() {
        NaturalRelations graph = new NaturalRelations() {
            @Override
            public Optional<CallNode> callees(String library, String object, int depth) {
                return Optional.of(CallNode.root(object, List.of(new CallNode("UNTERPRG", "ndv://" + library
                        + "/UNTERPRG", false, 4, "ndv://" + library + "/" + object, "CALLNAT", List.of()))));
            }

            @Override
            public Optional<CallNode> callers(String library, String object, int depth) {
                return Optional.empty();
            }
        };
        SourceRelations withGraph = new SourceRelations(graph, (c, n) -> List.of(), SystemFunctions.defaults());

        SourceRelations.Analysis analysis = withGraph.analyze(Optional.of(SourceLanguage.NATURAL), NATURAL,
                "TESTLIB/UMSATZ.NSP", "TESTLIB");

        assertThat(analysis.resources().sections().keySet()).anyMatch(k -> k.contains("USING"));
        assertThat(analysis.resources().sections().keySet()).noneMatch(k -> k.contains("CALLNAT"));
        assertThat(analysis.callees().objectName()).isEqualTo("UMSATZ");
        assertThat(analysis.callees().root().orElseThrow().children()).extracting(CallNode::kind)
                .containsExactly("CALLNAT");
        assertThat(analysis.callers().placeholder()).startsWith("Graph wird beim Öffnen der Bibliothek erstellt.");
    }

    @Test
    void withoutLibraryNaturalHasNoGraph() {
        SourceRelations.Analysis analysis = relations.analyze(Optional.of(SourceLanguage.NATURAL), NATURAL,
                "UMSATZ.NSP", null);

        assertThat(analysis.callees().placeholder()).isEqualTo("Bibliothek unbekannt — kein Call-Graph.");
    }

    @Test
    void cobolListsExternalCalls() {
        SourceRelations cobol = new SourceRelations(NaturalRelations.NONE,
                (c, n) -> List.of(new CobolCalls.Call("CALL", "UNTERPRG", 9)), SystemFunctions.defaults());

        SourceRelations.Analysis analysis = cobol.analyze(Optional.of(SourceLanguage.COBOL), "x", "BEISPIEL.cbl",
                null);

        assertThat(analysis.callees().root().orElseThrow().children()).extracting(CallNode::label)
                .containsExactly("CALL UNTERPRG");
    }

    @Test
    void systemFunctionsLinkToWikipediaAndRoundTrip() {
        SystemFunctions functions = SystemFunctions.defaults();

        assertThat(functions.wikipedia("idcams").toString())
                .isEqualTo("https://de.wikipedia.org/wiki/Access_Method_Services");
        assertThat(functions.wikipedia("UNBEKANNT").toString()).isEqualTo("https://de.wikipedia.org/wiki/UNBEKANNT");
        assertThat(SystemFunctions.parse(functions.serialize()).entries()).isEqualTo(functions.entries());
        assertThat(SystemFunctions.parse("").entries()).hasSize(functions.entries().size());
    }

    private static void collectTargets(CallNode node, List<String> out) {
        if (node.target() != null) {
            out.add(node.target());
        }
        node.children().forEach(child -> collectTargets(child, out));
    }
}
