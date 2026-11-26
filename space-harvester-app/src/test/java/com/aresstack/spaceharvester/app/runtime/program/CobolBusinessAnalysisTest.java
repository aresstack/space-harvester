package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CobolBusinessAnalysisTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, List<String>> LIBRARIES = Map.of("ftp", List.of("APP.CPY"));
    @TempDir Path home;

    @Test
    void persistsOnlyGroundedHypothesesAndInvalidatesChangedCopybooks() throws Exception {
        var program = snapshot("APP.COBOL(DEMO)", """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY CUSTOMER.
                       PROCEDURE DIVISION.
                           MOVE 'A' TO CUSTOMER-STATUS.
                           GOBACK.
                """);
        var copy = snapshot("APP.CPY(CUSTOMER)", "       01 CUSTOMER-STATUS PIC X.\n");
        var changed = snapshot("APP.CPY(CUSTOMER)", "       01 CUSTOMER-STATUS PIC XX.\n");
        var released = new ArrayList<PageKey>();
        var allowed = new AtomicBoolean(true);
        var dependencyReadable = new AtomicBoolean(true);
        var requests = new AtomicInteger();
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            cache.save(program);
            cache.save(copy);
            var enrichments = enrichments(cache, store);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            assertThat(enrichments.recompute(program, CobolBusinessEnrichment.ID).computed()).isZero();
            assertThat(store.find(program.key())).hasSize(1);
            var useCase = new CobolBusinessUseCase(enrichments,
                    key -> key.equals(copy.key()) && !dependencyReadable.get() ? Optional.empty()
                            : cache.find(key).map(page -> new DeliveredPage(page.header(), page, null, false, null, null)),
                    source -> allowed.get(), page -> { }, (space, key) -> released.add(key), Clock.systemUTC());
            AiChat model = (request, listener) -> {
                requests.incrementAndGet();
                assertThat(released).contains(program.key(), copy.key());
                assertThat(request.tools()).isEmpty();
                var facts = JSON.readTree(request.messages().getLast().text()).path("facts");
                var fact = facts.get(0);
                for (var candidate : facts) {
                    if (candidate.path("location").path("start").path("source").asString().equals("APP.CPY(CUSTOMER)")) {
                        fact = candidate;
                        break;
                    }
                }
                listener.completed(JSON.writeValueAsString(Map.of("hypotheses", List.of(Map.of(
                        "statement", "Mögliche Statusverwaltung", "reason", "Technischer Beleg, fachlich unbestätigt",
                        "evidence", List.of(fact.path("id").asString()))))));
                return () -> { };
            };
            allowed.set(false);
            assertThatThrownBy(() -> useCase.run(program.key(), program.payload().rawSha256(),
                    () -> model, "test-model", 4096, new Context())).isInstanceOf(IllegalStateException.class);
            assertThat(requests).hasValue(0);
            assertThat(released).isEmpty();
            allowed.set(true);
            dependencyReadable.set(false);
            assertThatThrownBy(() -> useCase.run(program.key(), program.payload().rawSha256(),
                    () -> model, "test-model", 4096, new Context())).isInstanceOf(IllegalStateException.class);
            assertThat(requests).hasValue(0);
            assertThat(released).isEmpty();
            dependencyReadable.set(true);
            assertThat(useCase.run(program.key(), program.payload().rawSha256(), () -> model, "test-model", 1024,
                    new Context()).computed()).isEqualTo(1);
            assertThat(requests.get()).isGreaterThan(1);
            assertThat(released).containsExactlyInAnyOrder(program.key(), copy.key());
            var business = store.find(program.key()).stream().filter(value -> value.processorId().equals(CobolBusinessEnrichment.ID))
                    .findFirst().orElseThrow();
            var artifact = CobolBusinessEnrichment.read(business.payloadJson());
            assertThat(artifact.result().hypotheses()).isNotEmpty();
            assertThat(artifact.result().hypotheses().stream().flatMap(claim -> claim.evidence().stream()))
                    .anyMatch(fact -> fact.location().start().source().equals("APP.CPY(CUSTOMER)"));
            AiChat revoked = (request, listener) -> {
                allowed.set(false);
                listener.completed("{\"hypotheses\":[]}");
                return () -> { };
            };
            assertThatThrownBy(() -> useCase.run(program.key(), program.payload().rawSha256(),
                    () -> revoked, "test-model", 4096, new Context())).isInstanceOf(IllegalStateException.class);
            assertThat(store.find(program.key())).contains(business);
            allowed.set(true);
            AiChat invalid = (request, listener) -> {
                listener.completed("{\"hypotheses\":[{\"statement\":\"Falsch\",\"reason\":\"Falsch\",\"evidence\":[\"F999999\"]}]}");
                return () -> { };
            };
            assertThatThrownBy(() -> useCase.run(program.key(), program.payload().rawSha256(),
                    () -> invalid, "test-model", 4096, new Context())).isInstanceOf(IllegalStateException.class);
            assertThat(store.find(program.key())).contains(business);
            dependencyReadable.set(false);
            assertThat(useCase.visible(new DeliveredPage(program.header(), program, null, false, null, null),
                    business, enrichments.view(program))).isFalse();
            dependencyReadable.set(true);
            cache.save(changed);
            assertThat(enrichments.view(program)).anyMatch(view -> view.enrichment().processorId().equals(CobolBusinessEnrichment.ID)
                    && view.freshness() == Enrichment.Freshness.STALE);
        }
        try (var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            assertThat(store.find(program.key())).anyMatch(value -> value.processorId().equals(CobolBusinessEnrichment.ID));
        }
    }

    @Test
    void rejectsUnknownOrAbsentEvidenceInsteadOfSavingUnsupportedClaims() {
        var position = new com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position("DEMO", 2, 8);
        var fact = new CobolBusinessAnalysis.Evidence("F1", "PROGRAM", "DEMO",
                new com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Span(position, position));
        assertThat(CobolBusinessAnalysis.validate(
                "{\"hypotheses\":[{\"statement\":\"Hypothese\",\"reason\":\"Begründung\",\"evidence\":[\"F1\"]}]}",
                List.of(fact))).singleElement().satisfies(claim -> assertThat(claim.evidence()).containsExactly(fact));
        for (String response : List.of("{}", "{\"hypotheses\":[{\"statement\":\"x\",\"reason\":\"y\",\"evidence\":[]}]}",
                "{\"hypotheses\":[{\"statement\":\"x\",\"reason\":\"y\",\"evidence\":[\"F2\"]}]}",
                "not json")) {
            assertThatThrownBy(() -> CobolBusinessAnalysis.validate(response, List.of(fact))).isInstanceOf(IllegalStateException.class);
        }
    }

    private static Enrichments enrichments(LucenePageCache cache, LuceneEnrichmentStore store) {
        return new Enrichments(new KnowledgeDocumentTextProjector(), List.of(
                new CobolProgramEnrichment(LIBRARIES, cache::find),
                new CobolBusinessEnrichment(LIBRARIES, cache::find)), store, Clock.systemUTC());
    }

    private static PageSnapshot snapshot(String path, String code) throws Exception {
        String resource = "ftp:host:21/" + path;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        var metadata = new PageMetadata(id, resource, path, "ftp", null, revision,
                null, null, PageMetadata.sorted(Map.of("ftp.path", path)));
        return PageSnapshot.of("ftp", metadata, new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider.SOURCE_TEXT_FORMAT), Instant.now());
    }

    private static final class Context implements JobContext {
        @Override public String jobId() { return "test"; }
        @Override public void checkpoint() { }
        @Override public boolean cancelRequested() { return false; }
        @Override public void discovered(long count) { }
        @Override public void processed(long count) { }
        @Override public void written(long count) { }
        @Override public void failed(long count) { }
    }
}
