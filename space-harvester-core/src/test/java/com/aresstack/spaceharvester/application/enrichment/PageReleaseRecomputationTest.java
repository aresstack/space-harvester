package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.FakeEmbeddingProvider;
import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * KI-Freigabe je Seite (ADR 0028) in den Anreicherungen: Eine Seite der Liste wird eingebettet und analysiert, auch
 * wenn ihre Quelle nicht freigegeben ist; Eintragen rechnet sie mit Modell, Entfernen ohne und löscht ihr Embedding.
 */
class PageReleaseRecomputationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T20:00:00Z"), ZoneOffset.UTC);
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final PageReleases releases = PageReleases.inMemory();
    private final AiRelease release = new AiRelease("offen"::equals, releases);
    private final EmbeddingProcessor embedding = new EmbeddingProcessor(new FakeEmbeddingProvider("m", 2), 0,
            KnowledgeTextSegmentation.defaults(), "offen"::equals).withPageReleases(releases::contains);

    @Test
    void aPageOfTheListIsEmbeddedAlthoughItsSourceIsNotReleased() {
        PageKey listed = new PageKey("intern", "1");
        PageKey other = new PageKey("intern", "2");
        releases.add(List.of(entry(listed)));

        assertThat(embedding.covers(listed)).isTrue();
        assertThat(embedding.covers(other)).isFalse();
        assertThat(embedding.covers("intern")).as("die Quelle als Ganzes bleibt zu").isFalse();
        assertThat(embedding.covers(new PageKey("offen", "9"))).isTrue();
        PageText page = new PageText("t/v1", "Ein Satz über den Betrieb.", List.of());
        assertThat(embedding.process(listed, page).payloadJson()).isNotBlank();
        assertThatThrownBy(() -> embedding.process(other, page)).isInstanceOf(ProcessingNotAllowedException.class);
    }

    @Test
    void releasingComputesWithTheModelAndRemovingDeletesTheEmbedding() throws Exception {
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(embedding), store, CLOCK);
        PageSnapshot page = text("intern", "1", "1", "Ein Satz über den Betrieb.");
        Map<PageKey, PageSnapshot> cache = Map.of(page.key(), page);
        enrichments.recompute(page, null);
        assertThat(store.find(page.key())).extracting(Enrichment::processorId).doesNotContain("embedding");

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments,
                sourceId -> cache.values().iterator(), embedding::covers, List.of("embedding"))) {
            releases.subscribe(change -> recomputation.recomputePages(Stream.concat(change.added().stream(),
                            change.removed().stream()).map(PageReleases.Entry::key)
                    .filter(key -> !release.sourceReleased(key.sourceId())).toList(),
                    key -> Optional.ofNullable(cache.get(key))));

            releases.add(List.of(entry(page.key())));
            eventually(() -> has(store, page.key()));

            releases.remove(List.of(page.key()));
            eventually(() -> !has(store, page.key()));
        }
    }

    private static boolean has(InMemoryEnrichmentStore store, PageKey key) {
        return store.find(key).stream().anyMatch(found -> found.processorId().equals("embedding")
                && found.status() == Enrichment.Status.OK);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 10 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static PageReleases.Entry entry(PageKey key) {
        return new PageReleases.Entry(key, "DEV", null, PageReleases.CHAT, CLOCK.instant());
    }
}
