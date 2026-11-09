package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
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
