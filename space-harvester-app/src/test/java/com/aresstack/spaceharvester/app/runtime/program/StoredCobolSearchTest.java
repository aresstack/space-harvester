package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class StoredCobolSearchTest {
    private static final List<String> LIBRARIES = List.of("APP.CPY");
    @TempDir Path home;

    @Test
    void readsPersistedFactsAfterRestartAndExcludesStaleOrUnreadableDependencies() throws Exception {
        var a = snapshot("ftp", "APP.COBOL(A)", program("A", "           COPY CUSTOMER.\n", "           CALL 'B'.\n"));
        var b = snapshot("ftp", "APP.COBOL(B)", program("B", "", "           CALL 'A'.\n"));
        var copy = snapshot("ftp", "APP.CPY(CUSTOMER)", "       01 CUSTOMER-FIELD PIC X(10).\n");
        var unused = snapshot("ftp", "APP.CPY(UNUSED)", "       01 UNUSED-FIELD PIC X.\n");
        var missing = snapshot("ftp", "APP.COBOL(C)", program("C", "", ""));
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            for (var page : List.of(a, b, copy, unused, missing)) cache.save(page);
            var enrichments = enrichments(cache, store);
            assertThat(enrichments.refresh(a).computed()).isEqualTo(1);
            assertThat(enrichments.refresh(b).computed()).isEqualTo(1);
            store.flush();
        }
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            var reader = new LucenePageCacheReader(cache);
            var enrichments = enrichments(cache, store);
            var search = new StoredCobolSearch(key -> reader.page(key).map(DeliveredPage::cached),
                    page -> enrichments.view(page.snapshot()));
            var result = search.build("ftp", LIBRARIES, visitor -> visit(reader, visitor), () -> false);
            assertThat(result.programs()).isEqualTo(2);
            assertThat(result.missingAnalysis()).isEqualTo(1);
            assertThat(result.search().search(CobolSearch.Mode.DATA, "CUSTOMER-FIELD", 10, 0).hits())
                    .singleElement().satisfies(fact -> {
                        assertThat(fact.source()).isEqualTo("APP.CPY(CUSTOMER)");
                        assertThat(fact.line()).isEqualTo(1);
                        assertThat(fact.attributes()).containsEntry("PAGE_ID", copy.key().pageId())
                                .containsEntry("SOURCE_RAW_SHA256", copy.payload().rawSha256());
                    });
            assertThat(result.search().search(CobolSearch.Mode.COPYBOOK, "", 10, 0).hits())
                    .hasSize(2).anySatisfy(fact -> {
                        assertThat(fact.name()).isEqualTo("CUSTOMER");
                        assertThat(fact.kind()).isEqualTo(CobolSearch.Kind.COPYBOOK);
                        assertThat(fact.attributes()).containsEntry("PAGE_ID", copy.key().pageId());
                    });
            assertThat(result.search().search(CobolSearch.Mode.COPYBOOK, "UNUSED", 10, 0).hits())
                    .singleElement().satisfies(fact -> assertThat(fact.kind()).isEqualTo(CobolSearch.Kind.COPYBOOK_CANDIDATE));
            var hierarchy = result.search().search(CobolSearch.Mode.CALLEES_HIERARCHY, "A", 20, 0);
            assertThat(hierarchy.hits()).hasSize(3).anyMatch(fact -> fact.attributes().containsKey("ALREADY_VISITED"));

            var restricted = new StoredCobolSearch(key -> key.equals(copy.key()) ? Optional.empty()
                    : reader.page(key).map(DeliveredPage::cached), page -> enrichments.view(page.snapshot()));
            var restrictedResult = restricted.build("ftp", LIBRARIES,
                    visitor -> visit(reader, header -> { if (!header.key().equals(copy.key())) visitor.accept(header); }), () -> false);
            assertThat(restrictedResult.programs()).isEqualTo(1);
            assertThat(restrictedResult.search().search(CobolSearch.Mode.DATA, "CUSTOMER-FIELD", 10, 0).hits()).isEmpty();

            cache.save(snapshot("ftp", "APP.CPY(CUSTOMER)", "       01 CHANGED-FIELD PIC X.\n"));
            var updated = search.build("ftp", LIBRARIES, visitor -> visit(reader, visitor), () -> false);
            assertThat(updated.programs()).isEqualTo(1);
            assertThat(updated.search().search(CobolSearch.Mode.CALLERS, "B", 10, 0).hits()).isEmpty();
        }
    }

    @Test
    void searchesMoreThanFiveHundredProgramsWithoutReparsingOrDroppingResults() throws Exception {
        Map<PageKey, DeliveredPage> pages = new LinkedHashMap<>();
        Map<PageKey, List<Enrichments.View>> results = new LinkedHashMap<>();
        var json = JsonMapper.builder().build();
        for (int i = 0; i < 530; i++) {
            String name = String.format("P%04d", i);
            var snapshot = snapshot("ftp", "APP.COBOL(" + name + ")", program(name, "", ""));
            var position = new CobolAnalysis.Position(snapshot.metadata().attributes().get("ftp.path"), 2, 8);
            var analysis = new CobolAnalysis(List.of(new CobolAnalysis.Element(CobolAnalysis.Kind.PROGRAM_ID, name,
                    -1, new CobolAnalysis.Span(position, position), name, Map.of())), List.of(), List.of(), List.of(), true);
            var artifact = new CobolProgramEnrichment.Artifact(position.source(), snapshot.payload().rawSha256(),
                    analysis, LIBRARIES, List.of());
            var enrichment = new Enrichment(snapshot.key(), CobolProgramEnrichment.ID, CobolProgramEnrichment.VERSION,
                    "input", snapshot.revision(), Enrichment.Status.OK, json.writeValueAsString(artifact), List.of(),
                    null, Instant.now());
            pages.put(snapshot.key(), new DeliveredPage(snapshot.header(), snapshot, null, false, null, null));
            results.put(snapshot.key(), List.of(new Enrichments.View(enrichment, Enrichment.Freshness.CURRENT)));
        }
        var search = new StoredCobolSearch(key -> Optional.ofNullable(pages.get(key)),
                page -> results.get(page.header().key()));
        var result = search.build("ftp", LIBRARIES,
                visitor -> pages.values().forEach(page -> visitor.accept(page.header())), () -> false);
        assertThat(result.programs()).isEqualTo(530);
        var last = result.search().search(CobolSearch.Mode.PROGRAM, "", 30, 500);
        assertThat(last.total()).isEqualTo(530);
        assertThat(last.hits()).hasSize(30).anyMatch(fact -> fact.name().equals("P0529"));
    }

    private static void visit(LucenePageCacheReader reader, Consumer<PageHeader> visitor) {
        String cursor = null;
        while (true) {
            var batch = reader.sourceHeaders("ftp", cursor, 2);
            if (batch.isEmpty()) return;
            batch.forEach(visitor);
            cursor = batch.getLast().key().pageId();
        }
    }

    private static Enrichments enrichments(LucenePageCache cache, LuceneEnrichmentStore store) {
        return new Enrichments(new KnowledgeDocumentTextProjector(),
                List.of(new CobolProgramEnrichment(Map.of("ftp", LIBRARIES), cache::find)), store, Clock.systemUTC());
    }

    private static String program(String name, String data, String procedure) {
        return "       IDENTIFICATION DIVISION.\n"
                + "       PROGRAM-ID. " + name + ".\n"
                + "       DATA DIVISION.\n"
                + "       WORKING-STORAGE SECTION.\n"
                + data
                + "       PROCEDURE DIVISION.\n"
                + procedure
                + "           GOBACK.\n";
    }

    private static PageSnapshot snapshot(String source, String path, String code) throws Exception {
        String resource = "ftp:host:21/" + path;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8)));
        PageMetadata metadata = new PageMetadata(id, resource, path, source, null, revision,
                null, null, PageMetadata.sorted(Map.of("ftp.path", path)));
        String payload = "# " + path + "\n\n```\n" + code + "\n```";
        return PageSnapshot.of(source, metadata, new NativePayload(payload.getBytes(StandardCharsets.UTF_8),
                "text/markdown", NativePayload.DOCUMENT_TEXT), Instant.now());
    }
}
