package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link ReleaseRecomputation#reconcileOnStart}: nur nicht passende Ergebnisse, nur Prozessoren, die gelten. */
class ReleaseRecomputationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T07:00:00Z"), ZoneOffset.UTC);
    private static final String NOT_APPROVED = "source-not-approved";
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    @Test
    void atTheStartOnlyTheProcessorsThatDoNotFitRecomputeAndOnesThatDoNotApplyNeverCount() throws Exception {
        Counting embedding = new Counting("embedding", "{\"vectors\":[[0.1]]}");
        Counting navigation = new Counting("navigation", "{\"analysis\":\"SEMANTIC\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(embedding, navigation), store, CLOCK);
        List<PageSnapshot> pages = List.of(text("wiki", "p1", "1", "Eins"), text("wiki", "p2", "1", "Zwei"),
                text("wiki", "p3", "1", "Drei"));
        // p1 passt; p2 fehlt die Navigation (Bestand von vorher); p3 trägt noch den Rückfall ohne Freigabe.
        enrichments.recompute(pages.get(0), null);
        enrichments.recompute(pages.get(1), "embedding");
        enrichments.recompute(pages.get(2), "embedding");
        store.save(new Enrichment(pages.get(2).key(), "navigation", "1", "x", "1", Enrichment.Status.OK,
                "{\"reason\":\"" + NOT_APPROVED + "\"}", List.of(), null, CLOCK.instant()));
        embedding.calls.clear();
        navigation.calls.clear();

        // „statements“ ist nicht eingerichtet: Sein fehlendes Ergebnis darf keinen Neulauf auslösen.
        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments,
                sourceId -> pages.iterator(), sourceId -> true, List.of("embedding", "navigation", "statements"))) {
            recomputation.reconcileOnStart(List.of("wiki"), () -> true, NOT_APPROVED);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (navigation.count("p3") == 0 && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        }

        assertThat(navigation.calls).containsOnlyKeys("p2", "p3");
        assertThat(embedding.calls).as("kein neues Embedding").isEmpty();
    }

    /** Zählt seine Aufrufe je Seite. */
    private static final class Counting implements EnrichmentProcessor {

        private final String id;
        private final String payload;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        private Counting(String id, String payload) {
            this.id = id;
            this.payload = payload;
        }

        int count(String pageId) {
            AtomicInteger count = calls.get(pageId);
            return count == null ? 0 : count.get();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EnrichmentOutput process(PageKey key, PageText text) {
            calls.computeIfAbsent(key.pageId(), ignored -> new AtomicInteger()).incrementAndGet();
            return EnrichmentOutput.of(payload);
        }
    }
}
