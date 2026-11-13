package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase;
import com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase.Scope;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON-LD-Export des Knowledge Graph (#499 Abschnitt G, ADR 0024). */
class KnowledgeGraphExportUseCaseTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");

    @TempDir
    Path out;

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Set<String> denied = new HashSet<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            scope.pageId() != null && denied.contains(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW);
    private final StatementAnalysis statements = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access, () -> parser,
            StatementAnalysisTest.clock());
    private final KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
            KnowledgeGraph.TermExpansion.NONE);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run,
            new JobLimits(2, 10, Duration.ofHours(1)), StatementAnalysisTest.clock());
    private final KnowledgeGraphExportUseCase export = new KnowledgeGraphExportUseCase(jobs, graph,
            key -> Optional.ofNullable(parents.get(key.pageId())), StatementAnalysisTest.clock(), "desktop");

    @Test
    void writesJsonLdWithContextGraphAndProvenanceOnlyForReadablePages() throws Exception {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        analyse("wiki", "2", "Die Buchhaltung prüft den Beleg.");
        denied.add("2");

        JobStatus done = run(new Scope.All());

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        JsonNode doc = read();
        assertThat(doc.has("@context")).isTrue();
        String text = doc.toString();
        assertThat(text).contains("sh:Statement").contains("sh:Mention").contains("prov:wasDerivedFrom")
                .contains("Sachbearbeiter").doesNotContain("Buchhaltung");
    }

    @Test
    void anAttachmentIsExportedAsItsOwnDocumentWithSeparateIdentifiers() throws Exception {
        // ADR 0026, Punkt 10 und Schlüsselform Punkt 1 (AP9c)
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        DocumentKey attachment = DocumentKey.attachment(new PageKey("wiki", "1"), "42");
        String input = "sha256:" + "e".repeat(64);
        store.replace(new PageStatementAnalysis(attachment.page(), null, "Anhang", "sha256:" + "c".repeat(64), input,
                parser.id(), parser.version(), java.time.Instant.parse("2026-11-13T12:00:00Z"),
                parser.analyze(PageStatementAnalysis.documentId(attachment, input), "Die Buchhaltung prüft den Antrag.",
                        () -> false), attachment));

        // Ohne Lesetext des Anhangs gilt seine Analyse als veraltet; der Export nimmt Veraltete hier mit.
        jobs.status(export.start(CALLER, new KnowledgeGraphExportUseCase.Request(new Scope.Page("wiki", "1"), true,
                out)).id());
        JsonNode doc = read();

        assertThat(nodes(doc, "Document")).singleElement().satisfies(node -> {
            assertThat(node.path("attachmentId").asString()).isEqualTo("42");
            assertThat(node.has("partId")).isFalse();
            assertThat(node.path("documentOf").toString()).contains("page:wiki:1");
            assertThat(node.path("@id").asString()).contains(attachment.fileName());
        });
        assertThat(nodes(doc, "Mention")).extracting(node -> node.path("@id").asString())
                .doesNotHaveDuplicates().anySatisfy(id -> assertThat(id).contains(attachment.fileName()));
        assertThat(doc.toString()).contains("Buchhaltung").contains("Sachbearbeiter");
    }

    @Test
    void theSubtreeFollowsTheParentChainAndTheExportNeverAnalyses() throws Exception {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        analyse("wiki", "2", "Die Buchhaltung prüft den Beleg.");
        analyse("wiki", "3", "Der Kunde sendet den Antrag.");
        parents.put("2", "1");
        int calls = parser.calls.get();

        assertThat(export.select(CALLER, new Scope.Subtree("wiki", "1"), false))
                .extracting(page -> page.page().key().pageId()).containsExactlyInAnyOrder("1", "2");
        run(new Scope.Page("wiki", "3"));

        assertThat(read().toString()).contains("Kunde").doesNotContain("Buchhaltung");
        assertThat(parser.calls.get()).isEqualTo(calls);
    }

    @Test
    void byDefaultOnlyPlausibleStatementsAreExportedWithQualityAndReview() throws Exception {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg. Den Antrag prüft er.");

        run(new Scope.Page("wiki", "1"));
        JsonNode doc = read();
        List<JsonNode> statementNodes = nodes(doc, "Statement");
        assertThat(statementNodes).singleElement().satisfies(node -> {
            assertThat(node.path("predicate").asString()).isEqualTo("übermittelt");
            assertThat(node.path("extractionQuality").asString()).isEqualTo("PLAUSIBLE");
            assertThat(node.path("verificationStatus").asString()).isEqualTo("UNVERIFIED_PARSER_OUTPUT");
            assertThat(node.has("paragraph")).isTrue();
        });
        assertThat(nodes(doc, "GraphExport").getFirst().path("exportedQuality").toString()).contains("PLAUSIBLE")
                .doesNotContain("UNCERTAIN");
        assertThat(doc.toString()).contains("Antrag").contains("analyzerVersion");

        Files.list(out).forEach(path -> path.toFile().delete());
        jobs.status(export.start(CALLER, new KnowledgeGraphExportUseCase.Request(new Scope.Page("wiki", "1"), false,
                out, Set.of(ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Quality.UNCERTAIN))).id());
        List<JsonNode> both = nodes(read(), "Statement");
        assertThat(both).hasSize(2);
        assertThat(both).filteredOn(node -> node.path("extractionQuality").asString().equals("UNCERTAIN"))
                .singleElement().satisfies(node -> assertThat(node.path("qualityReason").toString())
                        .contains("UNRESOLVED_PRONOUN"));
    }

    private static List<JsonNode> nodes(JsonNode doc, String type) {
        List<JsonNode> result = new java.util.ArrayList<>();
        doc.path("@graph").forEach(node -> {
            if (type.equals(node.path("@type").asString())) {
                result.add(node);
            }
        });
        return result;
    }

    private JobStatus run(Scope scope) {
        return jobs.status(export.start(CALLER, new KnowledgeGraphExportUseCase.Request(scope, false, out)).id());
    }

    private JsonNode read() throws Exception {
        try (Stream<Path> files = Files.list(out)) {
            List<Path> written = files.filter(path -> path.toString().endsWith(".jsonld")).toList();
            assertThat(written).hasSize(1);
            return JsonMapper.builder().build().readTree(Files.readString(written.get(0)));
        }
    }

    private void analyse(String sourceId, String pageId, String text) {
        PageSnapshot snapshot = EnrichmentTestPages.text(sourceId, pageId, "1", text);
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
    }
}
