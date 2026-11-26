package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.convert.PageAnalyses;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.export.EnrichmentAnalyses;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criteria of #1486 for authorization and currency of stored program analyses. */
class ProgramAnalysisAccessTest {
    private static final List<String> SYSLIB = List.of("APP.CPY1", "APP.CPY2");
    @TempDir Path home;

    private final Set<PageKey> unreadable = new HashSet<>();

    @Test
    void readableMainProgramNeverRevealsFactsOfAnUnreadableCopybook() throws Exception {
        var program = snapshot("APP.COBOL(MAIN)", program("MAIN", "           COPY CUSTOMER.\n"));
        var copybook = snapshot("APP.CPY2(CUSTOMER)", "       01 SECRET-FIELD PIC X(10).\n");
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            cache.save(program);
            cache.save(copybook);
            var enrichments = enrichments(cache, store, SYSLIB);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            var access = access(cache, enrichments);
            var page = delivered(cache, program.key());

            assertThat(access.current(page, CobolProgramEnrichment.ID)).get()
                    .satisfies(result -> assertThat(result.payloadJson()).contains("SECRET-FIELD"));

            unreadable.add(copybook.key());
            assertThat(access.current(page, CobolProgramEnrichment.ID)).isEmpty();
            assertThat(access.visible(page)).filteredOn(view -> CobolProgramEnrichment.ID.equals(view.enrichment().processorId()))
                    .singleElement().satisfies(view -> {
                        assertThat(view.freshness()).isEqualTo(Enrichment.Freshness.STALE);
                        assertThat(view.enrichment().payloadJson()).isEqualTo("{}");
                    });

            // Program search over the same gate finds the program but none of the copybook's fields.
            var search = new StoredCobolSearch(key -> readable(cache, key), access::visible)
                    .build("ftp", SYSLIB, visitor -> visit(cache, visitor), () -> false);
            assertThat(search.search().search(CobolSearch.Mode.DATA, "SECRET-FIELD", 10, 0).hits()).isEmpty();
            assertThat(search.programs()).isZero();

            // Exports know no reader, so results derived from other documents leave without content.
            var exported = new EnrichmentAnalyses(enrichments, null).of(program).sections().stream()
                    .filter(section -> CobolProgramEnrichment.ID.equals(section.processorId())).findFirst().orElseThrow();
            assertThat(exported.state()).isEqualTo(PageAnalyses.State.CURRENT);
            assertThat(exported.payloadJson()).isEqualTo("{}").doesNotContain("SECRET-FIELD");
            assertThat(exported.warnings()).contains("evidence-withheld");
        }
    }

    @Test
    void higherPriorityCopybookAppearingAfterTheSearchSnapshotRejectsTheOldHit() throws Exception {
        var program = snapshot("APP.COBOL(MAIN)", program("MAIN", "           COPY CUSTOMER.\n"));
        var lower = snapshot("APP.CPY2(CUSTOMER)", "       01 OLD-FIELD PIC X(10).\n");
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            cache.save(program);
            cache.save(lower);
            var enrichments = enrichments(cache, store, SYSLIB);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            var access = access(cache, enrichments);
            var snapshot = new StoredCobolSearch(key -> readable(cache, key), access::visible)
                    .build("ftp", SYSLIB, visitor -> visit(cache, visitor), () -> false);
            var hit = snapshot.search().search(CobolSearch.Mode.DATA, "OLD-FIELD", 10, 0).hits().getFirst();
            var origins = snapshot.evidenceFor(hit);
            assertThat(origins).anyMatch(origin -> origin.analysis() != null);
            assertThat(access.current(origins)).isTrue();

            // APP.CPY1 comes first in SYSLIB; its CUSTOMER was a negative probe and now exists. No hash of the
            // documents used so far changed, yet the hit must not be delivered as current any more.
            cache.save(snapshot("APP.CPY1(CUSTOMER)", "       01 NEW-FIELD PIC X(10).\n"));
            assertThat(access.current(origins)).isFalse();
            assertThat(access.current(delivered(cache, program.key()), CobolProgramEnrichment.ID)).isEmpty();
        }
    }

    @Test
    void changedOrDeletedCopybookChangedSyslibAndParserVersionInvalidate() throws Exception {
        var program = snapshot("APP.COBOL(MAIN)", program("MAIN", "           COPY CUSTOMER.\n"));
        var copybook = snapshot("APP.CPY2(CUSTOMER)", "       01 FIELD-A PIC X(10).\n");
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            cache.save(program);
            cache.save(copybook);
            var enrichments = enrichments(cache, store, SYSLIB);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            var page = delivered(cache, program.key());
            var stored = access(cache, enrichments).current(page, CobolProgramEnrichment.ID).orElseThrow();
            var origin = new StoredCobolSearch.Origin(program.key(), program.payload().rawSha256(),
                    ProgramAnalysisAccess.identity(stored));
            assertThat(access(cache, enrichments).current(origin)).isTrue();

            // Changed SYSLIB order: the same stored artifact is no longer current.
            var reordered = enrichments(cache, store, List.of("APP.CPY2", "APP.CPY1"));
            assertThat(access(cache, reordered).current(origin)).isFalse();

            // Another parser version: the stored artifact is not current.
            var older = new Enrichment(stored.key(), stored.processorId(), "0", stored.inputSha256(), stored.revision(),
                    stored.status(), stored.payloadJson(), stored.warnings(), null, stored.computedAt());
            store.save(older);
            assertThat(access(cache, enrichments).current(page, CobolProgramEnrichment.ID)).isEmpty();
            store.save(stored);
            assertThat(access(cache, enrichments).current(origin)).isTrue();

            // Changed copybook content.
            cache.save(snapshot("APP.CPY2(CUSTOMER)", "       01 FIELD-B PIC X(10).\n"));
            assertThat(access(cache, enrichments).current(origin)).isFalse();

            // Deleted copybook.
            cache.save(copybook);
            assertThat(access(cache, enrichments).current(origin)).isTrue();
            assertThat(cache.remove(copybook.key())).isTrue();
            assertThat(access(cache, enrichments).current(origin)).isFalse();

            // Withdrawn program source: nothing readable, nothing current.
            cache.save(copybook);
            unreadable.add(program.key());
            assertThat(access(cache, enrichments).current(origin)).isFalse();
        }
    }

    private ProgramAnalysisAccess access(LucenePageCache cache, Enrichments enrichments) {
        return new ProgramAnalysisAccess(key -> readable(cache, key), page -> enrichments.view(page.snapshot()),
                enrichments::evidence, (page, business, views) -> false);
    }

    private Optional<DeliveredPage> readable(LucenePageCache cache, PageKey key) {
        if (unreadable.contains(key)) return Optional.empty();
        return new LucenePageCacheReader(cache).page(key).map(DeliveredPage::cached);
    }

    private static DeliveredPage delivered(LucenePageCache cache, PageKey key) {
        return new LucenePageCacheReader(cache).page(key).map(DeliveredPage::cached).orElseThrow();
    }

    private static void visit(LucenePageCache cache, Consumer<PageHeader> visitor) {
        new LucenePageCacheReader(cache).sourceHeaders("ftp", null, 100).forEach(visitor);
    }

    private static Enrichments enrichments(LucenePageCache cache, LuceneEnrichmentStore store, List<String> syslib) {
        return new Enrichments(new KnowledgeDocumentTextProjector(),
                List.of(new CobolProgramEnrichment(Map.of("ftp", syslib), cache::find)), store, Clock.systemUTC());
    }

    private static String program(String name, String data) {
        return "       IDENTIFICATION DIVISION.\n"
                + "       PROGRAM-ID. " + name + ".\n"
                + "       DATA DIVISION.\n"
                + "       WORKING-STORAGE SECTION.\n"
                + data
                + "       PROCEDURE DIVISION.\n"
                + "           GOBACK.\n";
    }

    private static PageSnapshot snapshot(String path, String code) {
        String resource = "ftp:host:21/" + path;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        PageMetadata metadata = new PageMetadata(id, resource, path, "ftp", null, ProgramAnalysisAccess.sha256(code),
                null, null, PageMetadata.sorted(Map.of("ftp.path", path)));
        return PageSnapshot.of("ftp", metadata, new NativePayload(code.getBytes(StandardCharsets.UTF_8),
                "text/plain", com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider.SOURCE_TEXT_FORMAT),
                Instant.now());
    }
}
