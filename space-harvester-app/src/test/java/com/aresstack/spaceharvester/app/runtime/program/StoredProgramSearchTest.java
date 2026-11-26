package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredProgramSearchTest {
    @TempDir Path home;

    @Test
    void joinsOnlyTypedLiteralCandidatesAcrossReadableStoredSourcesAfterRestart() throws Exception {
        String program = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. BOOKING.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01 BOOKING-ID PIC 9.
                       PROCEDURE DIVISION.
                           CALL 'BOOKING'.
                           GOBACK.
                """;
        var cobol = ftp("ftp", "APP.COBOL(BOOKING)", program);
        var duplicate = ftp("ftp2", "OTHER.COBOL(BOOKING)", program);
        var jcl = ftp("ftp", "APP.JCL(JOB1)", "//JOB1 JOB\n//STEP EXEC PGM=BOOKING\n//IN DD DSN=APP.INPUT,DISP=SHR\n");
        var natural = ndv("ndv", "LIB", "MAIN.NSP", "CALLNAT 'BOOKING'\nCALLNAT #BOOKING\nCALL 'BOOKING'\nEND\n");
        var subprogram = ndv("ndv", "LIB", "BOOKING.NSN", "END\n");
        var otherType = ndv("ndv", "LIB", "BOOKING.NSP", "END\n");
        var stale = ndv("ndv", "LIB", "STALE.NSP", "CALLNAT 'BOOKING'\nEND\n");
        var missing = ndv("ndv", "LIB", "MISSING.NSP", "END\n");
        try (var cache = LucenePageCache.open(home.resolve("pages")); var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            var enrichments = enrichments(cache, store);
            for (var page : List.of(cobol, duplicate, jcl, natural, subprogram, otherType, stale, missing)) {
                cache.save(page);
                if (!page.key().equals(missing.key())) assertThat(enrichments.refresh(page).computed()).isEqualTo(1);
            }
            cache.save(ndv("ndv", "LIB", "STALE.NSP", "CALLNAT 'OTHER'\nEND\n"));
            store.flush();
        }
        try (var cache = LucenePageCache.open(home.resolve("pages")); var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            var reader = new LucenePageCacheReader(cache);
            var enrichments = enrichments(cache, store);
            Function<PageKey, Optional<DeliveredPage>> pages = key -> key.equals(duplicate.key()) ? Optional.empty()
                    : reader.page(key).map(DeliveredPage::cached);
            var search = new StoredProgramSearch(pages, page -> enrichments.view(page.snapshot()));
            for (String source : List.of("ftp", "ftp2")) {
                var cobolSearch = new StoredCobolSearch(pages, page -> enrichments.view(page.snapshot()));
                search.addCobol(cobolSearch.build(source, List.of(), visit -> reader.sourceHeaders(source, null, 50).forEach(visit),
                        () -> false), () -> false);
                search.addSource(source, false, visit -> reader.sourceHeaders(source, null, 50).forEach(visit), () -> false);
            }
            search.addSource("ndv", true, visit -> reader.sourceHeaders("ndv", null, 50).forEach(visit), () -> false);
            var snapshot = search.snapshot();
            assertThat(snapshot.search(Mode.OBJECTS, Language.NATURAL, null, "BOOKING", 20, 0).total()).isEqualTo(2);
            assertThat(snapshot.search(Mode.DATA, null, null, "BOOKING-ID", 20, 0).hits()).singleElement()
                    .satisfies(hit -> assertThat(hit.fact().location().key()).isEqualTo(cobol.key()));
            assertThat(snapshot.search(Mode.RESOURCES, Language.JCL, null, "APP.INPUT", 20, 0).hits()).singleElement()
                    .satisfies(hit -> assertThat(hit.fact().location().line()).isEqualTo(3));
            assertThat(snapshot.search(Mode.CANDIDATES, Language.JCL, null, "BOOKING", 20, 0).hits()).singleElement()
                    .satisfies(hit -> {
                        assertThat(hit.fact().location().key()).isEqualTo(jcl.key());
                        assertThat(hit.candidate().location().key()).isEqualTo(cobol.key());
                    });
            var links = snapshot.search(Mode.CANDIDATES, Language.NATURAL, "ndv", "BOOKING", 20, 0);
            assertThat(links.hits()).hasSize(2).anySatisfy(hit -> {
                assertThat(hit.fact().location().line()).isEqualTo(1);
                assertThat(hit.candidate().location().key()).isEqualTo(subprogram.key());
            }).anySatisfy(hit -> {
                assertThat(hit.fact().location().line()).isEqualTo(3);
                assertThat(hit.candidate().location().key()).isEqualTo(cobol.key());
            });
            assertThat(snapshot.search(Mode.CANDIDATES, Language.NATURAL, "ndv", "BOOKING", 1, 1).hits())
                    .containsExactly(links.hits().get(1));
            assertThat(snapshot.search(Mode.CALLERS, Language.NATURAL, null, "BOOKING", 20, 0).hits()).hasSize(2);
            assertThat(snapshot.search(Mode.CALLEES, Language.NATURAL, null, "MAIN", 20, 0).hits()).hasSize(3);
            assertThat(snapshot.search(Mode.COBOL_CALLEES_HIERARCHY, Language.COBOL, "ftp", "BOOKING", 20, 0).hits())
                    .isNotEmpty();
            assertThat(snapshot.search(Mode.OBJECTS, null, null, "STALE", 20, 0).hits()).isEmpty();
            assertThat(snapshot.coverage()).anyMatch(line -> line.startsWith("ndv / Natural: 3 aktuelle Analysen"));
            assertThatThrownBy(() -> search.addSource("ndv", true, visit -> reader.sourceHeaders("ndv", null, 50).forEach(visit),
                    () -> true)).isInstanceOf(java.util.concurrent.CancellationException.class);
        }
    }

    private static Enrichments enrichments(LucenePageCache cache, LuceneEnrichmentStore store) {
        return new Enrichments(new KnowledgeDocumentTextProjector(), List.of(
                new CobolProgramEnrichment(Map.of("ftp", List.of(), "ftp2", List.of()), cache::find),
                new JclProgramEnrichment(Set.of("ftp", "ftp2")), new NaturalProgramEnrichment(Set.of("ndv"))), store, Clock.systemUTC());
    }

    private static PageSnapshot ftp(String source, String path, String code) {
        var payload = new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider.SOURCE_TEXT_FORMAT);
        return page(source, "ftp:host:21/" + path, path, payload.rawSha256().substring(7), Map.of("ftp.path", path), payload);
    }

    private static PageSnapshot ndv(String source, String library, String path, String code) {
        var payload = new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider.SOURCE_TEXT_FORMAT);
        return page(source, "ndv:host:2700/" + library + "/" + path, path, "date|size=20", Map.of("ndv.library", library), payload);
    }

    private static PageSnapshot page(String source, String resource, String title, String revision, Map<String, String> attributes, NativePayload payload) {
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        var metadata = new PageMetadata(id, resource, title, source, null, revision, null, null, PageMetadata.sorted(attributes));
        return PageSnapshot.of(source, metadata, payload, Instant.now());
    }
}
