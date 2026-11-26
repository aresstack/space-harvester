package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.program.CobolProgramEnrichment;
import com.aresstack.spaceharvester.app.runtime.program.StoredCobolSource;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ProgramAnalysisTabsTest {
    private static final String MEMBER = "APP.COBOL(DEMO)";
    private static final String CODE = "       IDENTIFICATION DIVISION.\n"
            + "       PROGRAM-ID. DEMO.\n"
            + "       DATA DIVISION.\n"
            + "       WORKING-STORAGE SECTION.\n"
            + "       01 CUSTOMER PIC X(10).\n"
            + "       PROCEDURE DIVISION.\n"
            + "           CALL 'BOOKING'.\n"
            + "           GOBACK.\n";
    @TempDir Path home;

    @Test
    void reopensStoredParserFactsAndSwitchesToolbarWithoutLosingTextTabs() throws Exception {
        PageSnapshot program = snapshot("ftp", CODE, CODE);
        PageSnapshot changed = snapshot("ftp", CODE.replace("BOOKING", "PAYMENT"), CODE.replace("BOOKING", "PAYMENT"));
        assertThat(StoredCobolSource.read(program)).hasValueSatisfying(source ->
                assertThat(source.content()).isEqualTo(CODE));

        try (var store = LuceneEnrichmentStore.open(home)) {
            Enrichments enrichments = enrichments(store);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            assertThat(enrichments.refresh(program).current()).isEqualTo(1);
            store.flush();
        }
        try (var store = LuceneEnrichmentStore.open(home)) {
            Enrichments enrichments = enrichments(store);
            var views = enrichments.view(program);
            assertThat(views).singleElement().satisfies(view -> {
                assertThat(view.freshness()).isEqualTo(Enrichment.Freshness.CURRENT);
                var artifact = CobolProgramEnrichment.read(view.enrichment().payloadJson());
                assertThat(artifact.analysis().controlFlow().procedures()).hasSize(1);
                assertThat(artifact.analysis().controlFlow().procedures().getFirst().edges())
                        .anyMatch(edge -> edge.kind() == com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow.EdgeKind.CALL_RETURN);
                assertThat(artifact.analysis().calls()).singleElement().satisfies(call -> {
                    assertThat(call.target()).isEqualTo("BOOKING");
                    assertThat(call.span().start().line()).isEqualTo(7);
                });
            });
            assertThat(enrichments.view(changed)).singleElement().satisfies(view ->
                    assertThat(view.freshness()).isEqualTo(Enrichment.Freshness.STALE));
            SwingUtilities.invokeAndWait(() -> {
                PillTabs tabs = new PillTabs().tab("Absätze", new JPanel()).tab("Aussagen", new JPanel());
                tabs.detachBar();
                ProgramAnalysisTabs analysis = new ProgramAnalysisTabs(tabs);
                assertThat(tabs.names()).containsExactly("Absätze", "Aussagen");
                assertThat(analysis.show(program, views)).isTrue();
                assertThat(tabs.names()).containsExactly("Programmstruktur", "Datenstrukturen", "Copybooks", "Aufrufe", "Diagnosen", "Kontrollfluss", "Datenfluss", "Fachliche Analyse");
                tabs.select("Kontrollfluss");
                assertThat(cells(tabs)).anyMatch(value -> value.toString().contains("CALL BOOKING")
                        || value.toString().contains("CALL 'BOOKING'"));
                tabs.select("Aufrufe");
                assertThat(cells(tabs)).contains("BOOKING");
                assertThat(analysis.show(changed, enrichments.view(changed))).isTrue();
                assertThat(tabs.selected()).isEqualTo("Aufrufe");
                assertThat(cells(tabs)).doesNotContain("BOOKING");
                analysis.reset();
                assertThat(tabs.names()).containsExactly("Absätze", "Aussagen");
                assertThat(tabs.selected()).isEqualTo("Absätze");
                tabs.select("Aufrufe");
                assertThat(tabs.selected()).isEqualTo("Absätze");
            });
        }
    }

    @Test
    void reopensJclAnalysisAndSwitchesDocumentSpecificTabs() throws Exception {
        String code = "//JOB JOB\n//STEP EXEC PGM=DEMO\n//IN DD DSN=APP.DATA.IN,DISP=SHR\n";
        var legacy = snapshot("ftp", code, code);
        var program = PageSnapshot.of("ftp", legacy.metadata(),
                new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                        com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider.SOURCE_TEXT_FORMAT),
                Instant.now());
        var changed = snapshot("ftp", code.replace("DEMO", "OTHER"), code.replace("DEMO", "OTHER"));
        var processor = new com.aresstack.spaceharvester.app.runtime.program.JclProgramEnrichment(Set.of("ftp"));
        try (var store = LuceneEnrichmentStore.open(home)) {
            var enrichments = new Enrichments(new KnowledgeDocumentTextProjector(), List.of(processor), store, Clock.systemUTC());
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            assertThat(enrichments.refresh(program).current()).isEqualTo(1);
            store.flush();
        }
        try (var store = LuceneEnrichmentStore.open(home)) {
            var enrichments = new Enrichments(new KnowledgeDocumentTextProjector(), List.of(processor), store, Clock.systemUTC());
            var views = enrichments.view(program);
            assertThat(views).singleElement().satisfies(view -> {
                assertThat(view.freshness()).isEqualTo(Enrichment.Freshness.CURRENT);
                var artifact = com.aresstack.spaceharvester.app.runtime.program.JclProgramEnrichment.read(view.enrichment().payloadJson());
                assertThat(artifact.analysis().dependencies()).hasSize(2);
            });
            SwingUtilities.invokeAndWait(() -> {
                var tabs = new PillTabs().tab("Absätze", new JPanel());
                var analysis = new ProgramAnalysisTabs(tabs);
                assertThat(analysis.show(program, views)).isTrue();
                assertThat(tabs.names()).containsExactly("Jobstruktur", "Programm- und Prozeduraufrufe", "Dataset-Abhängigkeiten", "JCL-Hinweise");
                tabs.select("Dataset-Abhängigkeiten");
                assertThat(cells(tabs)).contains("APP.DATA.IN", "SHR");
                assertThat(analysis.show(changed, enrichments.view(changed))).isTrue();
                assertThat(cells(tabs)).doesNotContain("APP.DATA.IN", "SHR");
                analysis.reset();
                assertThat(tabs.names()).containsExactly("Absätze");
            });
        }
    }

    @Test
    void reopensDataFlowAndClearsStaleRows() throws Exception {
        String code = CODE.replace("           CALL 'BOOKING'.", "           MOVE 'A' TO CUSTOMER.\n           DISPLAY CUSTOMER.");
        PageSnapshot program = snapshot("ftp", code, code);
        PageSnapshot changed = snapshot("ftp", code.replace("'A'", "'B'"), code.replace("'A'", "'B'"));
        try (var store = LuceneEnrichmentStore.open(home)) {
            assertThat(enrichments(store).refresh(program).computed()).isEqualTo(1);
            store.flush();
        }
        try (var store = LuceneEnrichmentStore.open(home)) {
            var enrichments = enrichments(store);
            var views = enrichments.view(program);
            var stored = CobolProgramEnrichment.read(views.getFirst().enrichment().payloadJson()).analysis();
            var flow = stored.dataFlow();
            assertThat(flow.operations()).extracting(op -> op.verb()).containsExactly("MOVE", "DISPLAY");
            assertThat(flow.readSites()).singleElement().satisfies(read ->
                    assertThat(com.aresstack.spaceharvester.mainframe.cobol.CobolReachingDefinitions.candidates(stored, read))
                            .singleElement().satisfies(candidate ->
                            assertThat(candidate.span().start().line()).isEqualTo(7)));
            SwingUtilities.invokeAndWait(() -> {
                var tabs = new PillTabs().tab("Absätze", new JPanel());
                var analysis = new ProgramAnalysisTabs(tabs);
                assertThat(analysis.show(program, views)).isTrue();
                tabs.select("Datenfluss");
                assertThat(cells(tabs)).contains("Schreiben", "Lesen", "'A'");
                assertThat(analysis.show(changed, enrichments.view(changed))).isTrue();
                assertThat(cells(tabs)).doesNotContain("Schreiben", "Lesen", "'A'", "Explizite Schreibstelle");
            });
        }
    }

    @Test
    void rendersActualStoredControlFlowWithMermaid() throws Exception {
        try (var store = LuceneEnrichmentStore.open(home)) {
            var program = snapshot("ftp", CODE, CODE);
            var enrichments = enrichments(store);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            var analysis = CobolProgramEnrichment.read(enrichments.view(program).getFirst()
                    .enrichment().payloadJson()).analysis();
            var graph = analysis.controlFlow().procedures().getFirst();
            String diagram = com.aresstack.spaceharvester.mainframe.diagram.CobolControlFlowDiagram.render(graph);
            String svg = com.aresstack.spaceharvester.mermaid.MermaidRenderer.getInstance().renderToSvg(diagram);
            assertThat(svg).contains("<svg", "CALL_RETURN", "BOOKING");
        }
    }

    @Test
    void nativeSourcePreservesColumnsAndRejectsMismatchedRevision() throws Exception {
        String code = CODE.replace("DEMO.\n", "DEMO.  \n000300* e\u0301 ```  \n\n");
        var legacy = snapshot("ftp", code, code);
        var nativeSource = PageSnapshot.of("ftp", legacy.metadata(),
                new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                        com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider.SOURCE_TEXT_FORMAT),
                Instant.now());
        assertThat(StoredCobolSource.read(nativeSource)).hasValueSatisfying(source ->
                assertThat(source.content()).isEqualTo(code));
        assertThat(StoredCobolSource.read(legacy)).hasValueSatisfying(source ->
                assertThat(source.content()).isEqualTo(code));
        assertThat(nativeSource.key()).isEqualTo(legacy.key());
        var altered = PageSnapshot.of("ftp", nativeSource.metadata(), new NativePayload(
                (code + " ").getBytes(StandardCharsets.UTF_8), "text/plain", nativeSource.payload().nativeFormat()),
                Instant.now());
        assertThat(StoredCobolSource.verified(altered)).isEmpty();
    }

    @Test
    void rejectsAlteredPayloadAndNonProgramSource() throws Exception {
        assertThat(StoredCobolSource.read(snapshot("ftp", CODE.replace("BOOKING", "PAYMENT"), CODE))).isEmpty();
        PageSnapshot jcl = snapshot("ftp", "//JOB JOB\n//STEP EXEC PGM=DEMO\n", "//JOB JOB\n//STEP EXEC PGM=DEMO\n");
        assertThat(StoredCobolSource.read(jcl)).isEmpty();
        try (var store = LuceneEnrichmentStore.open(home)) {
            Enrichments enrichments = enrichments(store);
            assertThat(enrichments.refresh(jcl).computed()).isZero();
            assertThat(enrichments.refresh(snapshot("wiki", CODE, CODE)).computed()).isZero();
        }
    }

    private static Enrichments enrichments(LuceneEnrichmentStore store) {
        return new Enrichments(new KnowledgeDocumentTextProjector(), List.of(new CobolProgramEnrichment(Set.of("ftp"))),
                store, Clock.systemUTC());
    }

    private static PageSnapshot snapshot(String source, String code, String original) throws Exception {
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(original.getBytes(StandardCharsets.UTF_8)));
        PageMetadata metadata = new PageMetadata("demo", "ftp:host/" + MEMBER, "DEMO", source, null, revision,
                null, null, PageMetadata.sorted(Map.of("ftp.path", MEMBER)));
        String payload = "# " + MEMBER + "\n\n```\n" + code + "\n```";
        return PageSnapshot.of(source, metadata,
                new NativePayload(payload.getBytes(StandardCharsets.UTF_8), "text/markdown", NativePayload.DOCUMENT_TEXT),
                Instant.now());
    }

    private static List<Object> cells(Component component) {
        List<Object> values = new ArrayList<>();
        if (component instanceof JTable table) {
            for (int row = 0; row < table.getRowCount(); row++) {
                for (int column = 0; column < table.getColumnCount(); column++) {
                    values.add(table.getValueAt(row, column));
                }
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) values.addAll(cells(child));
        }
        return values;
    }
}
