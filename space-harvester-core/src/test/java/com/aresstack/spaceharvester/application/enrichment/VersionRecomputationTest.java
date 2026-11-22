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

/** {@link VersionRecomputation#reconcileOnStart}: nur ältere Versionen lokaler Prozessoren, die die Seite bearbeiten. */
class VersionRecomputationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T14:30:00Z"), ZoneOffset.UTC);
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    @Test
    void theStartRecomputesOnlyOlderVersionsOfLocalProcessors() throws Exception {
        Counting terms = new Counting("terms", false, true);
        Counting embedding = new Counting("embedding", true, true);
        Counting outline = new Counting("outline", false, false);
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(terms, embedding, outline), store, CLOCK);
        PageSnapshot current = text("wiki", "p1", "1", "Eins");
        PageSnapshot remote = text("wiki", "p2", "1", "Zwei");
        PageSnapshot notApplying = text("wiki", "p3", "1", "Drei");
        PageSnapshot missing = text("wiki", "p4", "1", "Vier");
        PageSnapshot old = text("wiki", "p5", "1", "Fünf");
        enrichments.recompute(current, "terms");
        // p2: nur das Embedding ist älter; es rechnet über das Netz und bleibt bei der Neuberechnung nach Freigabe.
        enrichments.recompute(remote, "terms");
        saveOld(remote, "embedding");
        // p3: outline bearbeitet keine Seite mehr; sein altes Ergebnis zählt hier nicht.
        enrichments.recompute(notApplying, "terms");
        saveOld(notApplying, "outline");
        // p4: kein Ergebnis von terms; es entsteht wie bisher beim nächsten Abgleich.
        // p5: terms aus der Version von vorher, wie nach einem Update.
        saveOld(old, "terms");
        terms.calls.clear();

        try (VersionRecomputation versions = new VersionRecomputation(enrichments,
                sourceId -> List.of(current, remote, notApplying, missing, old).iterator())) {
            versions.reconcileOnStart(List.of("wiki"));
            // p5 kommt zuletzt: Ist es gerechnet, sind alle Seiten geprüft.
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (terms.count("p5") == 0 && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        }

        assertThat(terms.calls).containsOnlyKeys("p5");
        assertThat(embedding.calls).isEmpty();
        assertThat(outline.calls).isEmpty();
        assertThat(store.find(old.key())).singleElement().satisfies(result -> {
            assertThat(result.processorId()).isEqualTo("terms");
            assertThat(result.processorVersion()).isEqualTo("1");
        });
    }

    @Test
    void withBackgroundJobsTheStartIsOneVisiblePartPerSourceThatPausesAndResumes() throws Exception {
        // ADR 0025 Nachtrag, Punkt 9: „Analysen nach Update nachrechnen“, je Quelle höchstens ein offener Teil.
        Counting terms = new Counting("terms", false, true);
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(terms), store, CLOCK);
        PageSnapshot current = text("wiki", "p1", "1", "Eins");
        PageSnapshot old = text("wiki", "p2", "1", "Zwei");
        enrichments.recompute(current, "terms");
        saveOld(old, "terms");
        terms.calls.clear();
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs
                .Jobs(new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), held::add,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(4, 10, java.time.Duration.ofHours(1)),
                CLOCK);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");

        try (VersionRecomputation versions = new VersionRecomputation(enrichments,
                sourceId -> sourceId.equals("wiki") ? List.of(current, old).iterator()
                        : List.<PageSnapshot>of().iterator())) {
            versions.background(background, sourceId -> sourceId.equals("wiki") ? 3 : 0);
            versions.reconcileOnStart(List.of("wiki", "ops"));
            versions.reconcileOnStart(List.of("wiki", "ops"));

            assertThat(background.groups()).as("der zweite Anlauf findet die offenen Teile").singleElement()
                    .satisfies(group -> {
                        assertThat(group.title()).isEqualTo(VersionRecomputation.BACKGROUND_TITLE);
                        assertThat(group.parts()).extracting(
                                com.aresstack.spaceharvester.application.jobs.JobStatus::sourceId)
                                .containsExactlyInAnyOrder("wiki", "ops");
                    });
            String groupId = background.groups().getFirst().groupId();

            background.pause(groupId);
            runAll(held);
            assertThat(terms.calls).as("angehalten rechnet nichts").isEmpty();

            background.resume(groupId);
            runAll(held);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (terms.count("p2") == 0 && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        }

        assertThat(terms.calls).containsOnlyKeys("p2");
        assertThat(background.group(background.groups().getFirst().groupId()).orElseThrow().completed()).isTrue();
        assertThat(jobs.list("app", com.aresstack.spaceharvester.application.jobs.JobType.BACKGROUND))
                .filteredOn(part -> part.sourceId().equals("wiki")).singleElement()
                .satisfies(part -> {
                    assertThat(part.progress().discovered()).isEqualTo(3);
                    assertThat(part.progress().processed()).as("auch die Seite nur mit Kopfdaten").isEqualTo(3);
                });
    }

    private static void runAll(java.util.Queue<Runnable> held) {
        Runnable next;
        while ((next = held.poll()) != null) {
            next.run();
        }
    }

    private void saveOld(PageSnapshot page, String processorId) {
        store.save(new Enrichment(page.key(), processorId, "0", "x", "1", Enrichment.Status.OK, "{}", List.of(), null,
                CLOCK.instant()));
    }

    /** Zählt seine Aufrufe je Seite. */
    private static final class Counting implements EnrichmentProcessor {

        private final String id;
        private final boolean remote;
        private final boolean applies;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        private Counting(String id, boolean remote, boolean applies) {
            this.id = id;
            this.remote = remote;
            this.applies = applies;
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
        public boolean appliesTo(PageKey key) {
            return applies;
        }

        @Override
        public boolean background() {
            return remote;
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EnrichmentOutput process(PageKey key, PageText text) {
            calls.computeIfAbsent(key.pageId(), ignored -> new AtomicInteger()).incrementAndGet();
            return EnrichmentOutput.of("{}");
        }
    }
}
