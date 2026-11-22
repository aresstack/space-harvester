package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
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

    @Test
    void atTheStartAnEmbeddingOfAnOlderVersionIsEmbeddedAgainOnlyForApprovedSources() throws Exception {
        Counting embedding = new Counting("embedding", "{\"vectors\":[[0.1]]}");
        Counting navigation = new Counting("navigation", "{\"analysis\":\"SEMANTIC\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(embedding, navigation), store, CLOCK);
        PageSnapshot current = text("wiki", "p1", "1", "Eins");
        PageSnapshot old = text("wiki", "p2", "1", "Zwei");
        PageSnapshot closed = text("intern", "p3", "1", "Drei");
        for (PageSnapshot page : List.of(current, old, closed)) {
            enrichments.recompute(page, null);
        }
        // p2 und p3 tragen ein Embedding der Zerlegung von vorher, ebenso eine ältere Navigation.
        for (PageSnapshot page : List.of(old, closed)) {
            store.save(new Enrichment(page.key(), "embedding", "0", "x", "1", Enrichment.Status.OK,
                    "{\"vectors\":[[0.2]]}", List.of(), null, CLOCK.instant()));
        }
        store.save(new Enrichment(old.key(), "navigation", "0", "x", "1", Enrichment.Status.OK,
                "{\"analysis\":\"SEMANTIC\"}", List.of(), null, CLOCK.instant()));
        embedding.calls.clear();
        navigation.calls.clear();

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments,
                sourceId -> sourceId.equals("wiki") ? List.of(current, old).iterator() : List.of(closed).iterator(),
                "wiki"::equals, List.of("embedding", "navigation"))) {
            recomputation.reconcileOnStart(List.of("wiki"), () -> true, NOT_APPROVED);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (embedding.count("p2") == 0 && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        }

        assertThat(embedding.calls).containsOnlyKeys("p2");
        assertThat(navigation.calls).as("andere Prozessoren nach Bedarf").isEmpty();
    }

    @Test
    void withoutAnEmbeddingModelAnOlderEmbeddingStaysAndCountsAsNothingDue() throws Exception {
        // Der Start läuft etwa für die Satzanalyse; embedding bearbeitet ohne Modell keine Seite.
        Counting embedding = new Counting("embedding", "{\"vectors\":[[0.1]]}", false);
        Counting navigation = new Counting("navigation", "{\"analysis\":\"SEMANTIC\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(embedding, navigation), store, CLOCK);
        PageSnapshot page = text("wiki", "p1", "1", "Eins");
        enrichments.recompute(page, "navigation");
        store.save(new Enrichment(page.key(), "embedding", "0", "x", "1", Enrichment.Status.OK,
                "{\"vectors\":[[0.2]]}", List.of(), null, CLOCK.instant()));
        PageSnapshot marker = text("wiki", "p2", "1", "Zwei");
        navigation.calls.clear();

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments,
                sourceId -> List.of(page, marker).iterator(), "wiki"::equals, List.of("embedding", "navigation"))) {
            recomputation.reconcileOnStart(List.of("wiki"), () -> true, NOT_APPROVED);
            // p2 fehlt die Navigation: Ist sie gerechnet, ist auch p1 schon geprüft.
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (navigation.count("p2") == 0 && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        }

        assertThat(navigation.calls).containsOnlyKeys("p2");
        assertThat(embedding.calls).isEmpty();
        assertThat(store.find(page.key())).extracting(Enrichment::processorId).contains("embedding");
    }

    @Test
    void aWithdrawnSourceHandsAllItsPagesToTheWithdrawalButTheStartNever() throws Exception {
        Counting navigation = new Counting("navigation", "{\"analysis\":\"STRUCTURE\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(navigation), store, CLOCK);
        List<PageSnapshot> pages = new java.util.ArrayList<>();
        for (int i = 0; i < ReleaseRecomputation.WITHDRAWAL_BATCH + 1; i++) {
            pages.add(text("intern", "p" + i, "1", "Seite " + i));
        }
        List<List<String>> withdrawn = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments, sourceId -> pages.iterator(),
                "wiki"::equals, List.of("navigation"))) {
            recomputation.withdrawal(batch -> withdrawn.add(batch.stream().map(page -> page.key().pageId())
                    .toList()));
            // Der Start nimmt nie etwas zurück: „einmalig beschreiben“ in einer gesperrten Quelle bleibt erhalten.
            recomputation.reconcileOnStart(List.of("intern"), () -> true, NOT_APPROVED);
            recomputation.recompute("intern", false);
            // Schließen beendet laufende Läufe nach der aktuellen Seite: vorher auf beide Teile warten.
            awaitUntil(() -> withdrawn.size() == 2);
        }

        assertThat(withdrawn).extracting(List::size).containsExactly(ReleaseRecomputation.WITHDRAWAL_BATCH, 1);
        assertThat(withdrawn.stream().flatMap(List::stream)).hasSize(pages.size()).doesNotHaveDuplicates();
    }

    @Test
    void withOnlyAVisionModelAWithdrawnSourceOnlyHandsItsPagesToTheWithdrawal() throws Exception {
        AiServiceSettings before = new AiServiceSettings(java.net.URI.create("http://localhost:8080/v1"), null, null,
                null, null, null, null, java.util.Set.of("wiki", "intern")).withVisionModel("vision-1");
        AiServiceSettings after = new AiServiceSettings(java.net.URI.create("http://localhost:8080/v1"), null, null,
                null, null, null, null, java.util.Set.of("wiki")).withVisionModel("vision-1");
        assertThat(ReleaseRecomputation.changes(before, after)).as("kein Embedding- und kein Chat-Modell").isEmpty();
        assertThat(ReleaseRecomputation.withdrawnImages(before, after, Map.of())).containsExactly("intern");
        assertThat(ReleaseRecomputation.withdrawnImages(before, after, Map.of("intern", false))).isEmpty();
        assertThat(ReleaseRecomputation.withdrawnImages(after, before, Map.of())).as("neu freigegeben").isEmpty();

        Counting navigation = new Counting("navigation", "{\"analysis\":\"STRUCTURE\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(navigation), store, CLOCK);
        List<PageSnapshot> pages = List.of(text("intern", "p1", "1", "Eins"), text("intern", "p2", "1", "Zwei"));
        List<String> withdrawn = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments, sourceId -> pages.iterator(),
                sourceId -> false, List.of("navigation"))) {
            recomputation.withdrawal(batch -> batch.forEach(page -> withdrawn.add(page.key().pageId())));
            recomputation.withdraw("intern");
            awaitUntil(() -> withdrawn.size() == 2);
        }

        assertThat(withdrawn).containsExactly("p1", "p2");
        assertThat(navigation.calls).as("nichts neu gerechnet").isEmpty();
    }

    @Test
    void pagesRemovedFromTheListGoToTheWithdrawalAddedOnesNot() throws Exception {
        Counting navigation = new Counting("navigation", "{\"analysis\":\"STRUCTURE\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(navigation), store, CLOCK);
        PageSnapshot added = text("intern", "neu", "1", "Neu");
        PageSnapshot removed = text("intern", "alt", "1", "Alt");
        Map<PageKey, PageSnapshot> cache = Map.of(added.key(), added, removed.key(), removed);
        List<String> withdrawn = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments,
                sourceId -> List.<PageSnapshot>of().iterator(), sourceId -> false, List.of("navigation"))) {
            recomputation.withdrawal(batch -> batch.forEach(page -> withdrawn.add(page.key().pageId())));
            recomputation.recomputePages(List.of(added.key()), List.of(removed.key(), new PageKey("intern", "weg")),
                    key -> Optional.ofNullable(cache.get(key)));
            awaitUntil(() -> !withdrawn.isEmpty());
        }

        assertThat(withdrawn).containsExactly("alt");
    }

    @Test
    void withBackgroundJobsTheStartCheckIsAVisiblePartPerSourceAndWithoutModelNone() throws Exception {
        Counting navigation = new Counting("navigation", "{\"analysis\":\"SEMANTIC\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(navigation), store, CLOCK);
        List<PageSnapshot> pages = List.of(text("wiki", "p1", "1", "Eins"), text("wiki", "p2", "1", "Zwei"));
        enrichments.recompute(pages.get(0), null);
        navigation.calls.clear();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs(Runnable::run);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments, sourceId -> pages.iterator(),
                sourceId -> true, List.of("navigation"))) {
            recomputation.background(background, key -> Optional.empty(), () -> true, NOT_APPROVED, sourceId -> 2);
            recomputation.reconcileOnStart(List.of("wiki"), () -> false, NOT_APPROVED);
            assertThat(background.groups()).as("ohne Modell keine Gruppe").isEmpty();

            recomputation.reconcileOnStart(List.of("wiki"), () -> true, NOT_APPROVED);
            awaitUntil(() -> navigation.count("p2") > 0);
        }

        assertThat(background.groups()).singleElement().satisfies(group -> {
            assertThat(group.title()).isEqualTo(ReleaseRecomputation.CHECK_TITLE);
            assertThat(group.parts()).singleElement().satisfies(part -> {
                assertThat(part.parameter(com.aresstack.spaceharvester.application.background.BackgroundJobs.KIND))
                        .isEqualTo(ReleaseRecomputation.BACKGROUND_KIND);
                assertThat(part.parameter(ReleaseRecomputation.MODE)).isEqualTo(ReleaseRecomputation.CHECK);
                assertThat(part.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
                assertThat(part.progress().processed()).isEqualTo(2);
            });
        });
        assertThat(navigation.calls).containsOnlyKeys("p2");
    }

    @Test
    void aResumedGrantPartRecomputesOnlyWhatDoesNotFitTheRelease() throws Exception {
        // Ein fortgesetzter Teil nach neuer Freigabe rechnet nicht alle Seiten erneut, nur nicht passende.
        Counting navigation = new Counting("navigation", "{\"analysis\":\"SEMANTIC\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(navigation), store, CLOCK);
        List<PageSnapshot> pages = List.of(text("wiki", "p1", "1", "Eins"), text("wiki", "p2", "1", "Zwei"));
        enrichments.recompute(pages.get(0), null);
        navigation.calls.clear();
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs(held::add);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments, sourceId -> pages.iterator(),
                sourceId -> true, List.of("navigation"))) {
            recomputation.background(background, key -> Optional.empty(), () -> true, NOT_APPROVED, sourceId -> 2);
            String groupId = background.start(ReleaseRecomputation.BACKGROUND_KIND, ReleaseRecomputation.CHANGE_TITLE,
                    List.of(new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", null,
                            Map.of(ReleaseRecomputation.MODE, ReleaseRecomputation.GRANT)))).groupId();
            background.pause(groupId);
            runAll(held);
            assertThat(navigation.calls).as("angehalten rechnet nichts").isEmpty();

            background.resume(groupId);
            runAll(held);
            awaitUntil(() -> navigation.count("p2") > 0
                    && background.group(groupId).orElseThrow().finished());
        }

        assertThat(navigation.calls).as("p1 passt schon").containsOnlyKeys("p2");
    }

    @Test
    void withBackgroundJobsChangedPagesOfTheReleaseListTravelAsParametersAndNoEmptyRemovedOne() throws Exception {
        Counting navigation = new Counting("navigation", "{\"analysis\":\"STRUCTURE\"}");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(navigation), store, CLOCK);
        PageSnapshot added = text("intern", "neu", "1", "Neu");
        PageSnapshot removed = text("ops", "alt", "1", "Alt");
        Map<PageKey, PageSnapshot> cache = Map.of(added.key(), added, removed.key(), removed);
        List<String> withdrawn = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs(Runnable::run), "app");

        try (ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments,
                sourceId -> List.<PageSnapshot>of().iterator(), sourceId -> false, List.of("navigation"))) {
            recomputation.withdrawal(batch -> batch.forEach(page -> withdrawn.add(page.key().pageId())));
            recomputation.background(background, key -> Optional.ofNullable(cache.get(key)), () -> true, NOT_APPROVED,
                    sourceId -> 0);
            recomputation.recomputePages(List.of(added.key()), List.of(removed.key()), key -> Optional.empty());
            awaitUntil(() -> !withdrawn.isEmpty());
        }

        assertThat(background.groups()).singleElement().satisfies(group -> {
            assertThat(group.title()).isEqualTo(ReleaseRecomputation.PAGES_TITLE);
            assertThat(group.parts()).hasSize(2);
            assertThat(group.parts()).filteredOn(part -> part.sourceId().equals("intern")).singleElement()
                    .satisfies(part -> {
                        assertThat(part.parameter(ReleaseRecomputation.PAGES)).isEqualTo("neu");
                        assertThat(part.parameters()).as("ohne entfernte Seiten keine leere Angabe")
                                .doesNotContainKey(ReleaseRecomputation.REMOVED);
                    });
            assertThat(group.parts()).filteredOn(part -> part.sourceId().equals("ops")).singleElement()
                    .satisfies(part -> assertThat(part.parameter(ReleaseRecomputation.REMOVED)).isEqualTo("alt"));
        });
        assertThat(withdrawn).containsExactly("alt");
    }

    private static com.aresstack.spaceharvester.application.jobs.Jobs jobs(java.util.concurrent.Executor executor) {
        return new com.aresstack.spaceharvester.application.jobs.Jobs(
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), executor,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(4, 10, java.time.Duration.ofHours(1)),
                CLOCK);
    }

    private static void runAll(java.util.Queue<Runnable> held) {
        Runnable next;
        while ((next = held.poll()) != null) {
            next.run();
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /** Zählt seine Aufrufe je Seite. */
    private static final class Counting implements EnrichmentProcessor {

        private final String id;
        private final String payload;
        private final boolean applies;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        private Counting(String id, String payload) {
            this(id, payload, true);
        }

        private Counting(String id, String payload, boolean applies) {
            this.id = id;
            this.payload = payload;
            this.applies = applies;
        }

        @Override
        public boolean appliesTo(PageKey key) {
            return applies;
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
