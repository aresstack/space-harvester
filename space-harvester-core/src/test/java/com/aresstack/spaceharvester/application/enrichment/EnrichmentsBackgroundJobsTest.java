package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Die Warteschlange der Netz-Prozessoren als Hintergrundauftrag „KI-Anreicherung“ (ADR 0025 Nachtrag, Punkt 10): je
 * Quelle ein Teil, anhaltbar, abbrechbar, ohne Verwerfen. Nur erfundene Quellen und Texte.
 */
class EnrichmentsBackgroundJobsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneOffset.UTC);
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final Map<PageKey, PageSnapshot> cache = new ConcurrentHashMap<>();

    @Test
    void eachSourceGetsOnePartThatTakesAllItsPagesAndTheSecondSourceWaitsBehindIt() throws Exception {
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            PageSnapshot a1 = page("wiki", "1", "Eins");
            PageSnapshot a2 = page("wiki", "2", "Zwei");
            PageSnapshot b1 = page("ops", "1", "Drei");

            enrichments.refresh(a1);
            enrichments.refresh(a2);
            enrichments.refresh(a1);
            enrichments.refresh(b1);

            assertThat(background.groups()).hasSize(2).allSatisfy(group -> {
                assertThat(group.title()).isEqualTo(Enrichments.BACKGROUND_TITLE);
                assertThat(group.parts()).singleElement().satisfies(part -> assertThat(
                        part.parameter(BackgroundJobs.KIND)).isEqualTo(Enrichments.BACKGROUND_KIND));
            });
            JobStatus wiki = part(background, "wiki");
            assertThat(part(background, "ops").parameter(Jobs.AFTER)).as("hintereinander wie der eine Thread")
                    .isEqualTo(wiki.id());
            assertThat(remote.calls).as("der Aufrufer rechnet nicht").isEmpty();

            runAll(held);

            assertThat(remote.calls).containsOnlyKeys("wiki/1", "wiki/2", "ops/1");
            assertThat(remote.calls.get("wiki/1").get()).as("dieselbe Seite einmal").isEqualTo(1);
            assertThat(jobs.listAll(JobType.BACKGROUND)).extracting(JobStatus::state)
                    .containsOnly(JobState.COMPLETED);
            assertThat(part(background, "wiki").progress().processed()).isEqualTo(2);
            assertThat(store.find(a2.key())).extracting(Enrichment::processorId).containsExactly("remote");

            // Danach startet die nächste Seite einen neuen Teil.
            enrichments.refresh(page("wiki", "3", "Vier"));
            assertThat(background.groups()).hasSize(3);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aPausedPartKeepsCollectingAndComputesOnResume() throws Exception {
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            // Ein laufender Teil davor: der Teil der Quelle wartet und lässt sich anhalten, bevor er läuft.
            enrichments.refresh(page("x", "1", "Vorne"));
            enrichments.refresh(page("wiki", "1", "Eins"));
            String groupId = part(background, "wiki").parameter(BackgroundJobs.GROUP);
            background.pause(groupId);
            enrichments.refresh(page("wiki", "2", "Zwei"));
            assertThat(background.parts(Enrichments.BACKGROUND_KIND)).as("der angehaltene Teil nimmt die neue Seite mit")
                    .hasSize(2);

            runAll(held);
            assertThat(remote.calls).containsOnlyKeys("x/1");
            background.resume(groupId);
            runAll(held);

            assertThat(remote.calls).containsOnlyKeys("x/1", "wiki/1", "wiki/2");
            assertThat(part(background, "wiki").state()).isEqualTo(JobState.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellingDropsTheWaitingWorkSaysSoAndTheNextPageStartsANewPart() throws Exception {
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("x", "1", "Vorne"));
            enrichments.refresh(page("wiki", "1", "Eins"));
            JobStatus first = part(background, "wiki");
            background.cancel(first.parameter(BackgroundJobs.GROUP));
            assertThat(jobs.events(first.id())).anySatisfy(event -> assertThat(event.message())
                    .endsWith("; " + Enrichments.CANCEL_NOTE));

            enrichments.refresh(page("wiki", "2", "Zwei"));
            runAll(held);

            assertThat(background.parts(Enrichments.BACKGROUND_KIND)).hasSize(3);
            assertThat(remote.calls).as("die abgebrochene Seite holt der nächste Abgleich nach")
                    .containsOnlyKeys("x/1", "wiki/2");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void forcedRecomputationsNeverWaitForASlotSoAPartOfTheBackgroundCannotBlockTheQueue() throws Exception {
        // Ein Teil (etwa „KI-Freigabe nachziehen“), der auf einen Platz aus forcedSlots wartete, hielte einen Platz des
        // Hintergrunds, den die KI-Anreicherung zum Freigeben braucht.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("x", "1", "Vorne"));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                for (int i = 0; i <= Enrichments.FORCED_IN_BACKGROUND; i++) {
                    assertThat(enrichments.recomputeInBackground(page("wiki", "p" + i, "Text " + i),
                            List.of("remote"))).isEqualTo(1);
                }
            });
            assertThat(part(background, "wiki").state()).isEqualTo(JobState.QUEUED);

            runAll(held);
            assertThat(remote.calls).hasSize(Enrichments.FORCED_IN_BACKGROUND + 2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void whenTheBackgroundTakesNoPartTheWorkGoesTheFormerWay() throws Exception {
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        Jobs jobs = jobs(Runnable::run, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            jobs.close();
            enrichments.refresh(page("wiki", "1", "Eins"));
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(background.groups()).isEmpty();
            assertThat(remote.calls).containsOnlyKeys("wiki/1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aFailedComputationCountsAsFailedInThePartNotAsWritten() throws Exception {
        // Codex-Befund #1077: Scheitert der Dienst, meldet der Teil das, statt fertig ohne Fehler zu wirken.
        Remote remote = new Remote();
        remote.failing.add("wiki/2");
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            PageSnapshot ok = page("wiki", "1", "Eins");
            PageSnapshot broken = page("wiki", "2", "Zwei");
            // Beide warten, bevor der Teil läuft.
            enrichments.refresh(ok);
            enrichments.refresh(broken);
            runAll(held);

            assertThat(part(background, "wiki").progress()).satisfies(progress -> {
                assertThat(progress.processed()).isEqualTo(2);
                assertThat(progress.written()).isEqualTo(1);
                assertThat(progress.failed()).isEqualTo(1);
            });
            assertThat(store.find(broken.key())).singleElement().satisfies(result -> assertThat(result.status())
                    .isEqualTo(Enrichment.Status.FAILED));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aForcedRecomputationThatFindsNoPlaceAnywhereRunsInTheCaller() throws Exception {
        // Codex-Befund #1077: Sonst sähe das alte Ergebnis weiter aktuell aus.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        Jobs jobs = jobs(Runnable::run, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            jobs.close();
            executor.shutdownNow();

            assertThat(enrichments.recomputeInBackground(page("wiki", "1", "Eins"), List.of("remote"))).isEqualTo(1);

            assertThat(remote.calls).containsOnlyKeys("wiki/1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void withASingleSlotTheBackgroundNeverRunsSoTheWorkGoesTheFormerWay() throws Exception {
        // Abnahme #1077: Mit einem Platz läuft kein Hintergrundteil; die KI-Anreicherung stünde sonst still.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        Jobs jobs = jobs(Runnable::run, 1);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("wiki", "1", "Eins"));
            assertThat(enrichments.recomputeInBackground(page("wiki", "2", "Zwei"), List.of("remote"))).isEqualTo(1);
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(background.groups()).isEmpty();
            assertThat(remote.calls).containsOnlyKeys("wiki/1", "wiki/2");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void workArrivingWhileAPartRunsGoesToANewPartBehindTheOtherSources() throws Exception {
        // Abnahme #1077: Ein Teil nimmt nur, was bei seinem Start wartete; sonst hielte steter Zufluss andere auf.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            PageSnapshot later = page("wiki", "2", "Zwei");
            remote.during.put("wiki/1", () -> enrichments.refresh(later));
            enrichments.refresh(page("wiki", "1", "Eins"));
            enrichments.refresh(page("ops", "1", "Drei"));

            runAll(held);

            assertThat(remote.order).containsExactly("wiki/1", "ops/1", "wiki/2");
            List<JobStatus> parts = background.parts(Enrichments.BACKGROUND_KIND);
            assertThat(parts).hasSize(3).extracting(JobStatus::sourceId)
                    .containsExactlyInAnyOrder("wiki", "ops", "wiki");
            JobStatus ops = part(background, "ops");
            JobStatus second = parts.stream().filter(part -> part.sourceId().equals("wiki")
                    && part.parameter(Jobs.AFTER) != null && part.parameter(Jobs.AFTER).contains(ops.id()))
                    .findFirst().orElseThrow();
            assertThat(second.parameter(Jobs.AFTER).split(",")).as("hinten in der Kette, hinter beiden").hasSize(2);
            assertThat(parts).extracting(JobStatus::state).containsOnly(JobState.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aPausedPartHoldsOnlyItsOwnSourceNotTheOthersBehindIt() throws Exception {
        // Abnahme #1077: Anhalten von A darf B nicht stoppen.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("x", "1", "Vorne"));
            enrichments.refresh(page("wiki", "1", "Eins"));
            JobStatus paused = part(background, "wiki");
            background.pause(paused.parameter(BackgroundJobs.GROUP));
            enrichments.refresh(page("ops", "1", "Drei"));
            assertThat(part(background, "ops").parameter(Jobs.AFTER)).contains(paused.id());

            runAll(held);

            assertThat(remote.calls).containsOnlyKeys("x/1", "ops/1");
            assertThat(part(background, "wiki").state()).isEqualTo(JobState.PAUSED);
            assertThat(part(background, "ops").state()).isEqualTo(JobState.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void partsFromBeforeTheRestartAreRetiredSoTheyHoldNoNewPart() throws Exception {
        // Abnahme #1077: Die Warteschlange lag im Speicher; ein angehaltener Teil von vorher hielte alle neuen auf.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs before = new Jobs(jobStore, held::add, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
        BackgroundJobs backgroundBefore = new BackgroundJobs(before, "app");
        try {
            Enrichments first = enrichments(remote, executor, backgroundBefore);
            first.refresh(page("x", "1", "Vorne"));
            first.refresh(page("wiki", "1", "Eins"));
            JobStatus old = part(backgroundBefore, "wiki");
            backgroundBefore.pause(old.parameter(BackgroundJobs.GROUP));
            assertThat(backgroundBefore.part(old.id()).orElseThrow().state()).isEqualTo(JobState.PAUSED);

            // Neustart: dieselbe Ablage, neue Auftragsverwaltung, leere Warteschlange.
            Jobs after = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs backgroundAfter = new BackgroundJobs(after, "app");
            Enrichments second = enrichments(remote, executor, backgroundAfter);

            assertThat(backgroundAfter.part(old.id()).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
            assertThat(after.events(old.id())).anySatisfy(event -> assertThat(event.message())
                    .isEqualTo(Enrichments.EMPTY_AFTER_RESTART));

            second.refresh(page("wiki", "2", "Zwei"));
            awaitUntil(() -> remote.calls.containsKey("wiki/2"));
            assertThat(remote.calls).containsKey("wiki/2").doesNotContainKey("wiki/1");
            assertThat(backgroundAfter.parts(Enrichments.BACKGROUND_KIND)).filteredOn(BackgroundJobs::open)
                    .isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aComputationThatThrowsIsReportedAndThePartGoesOnWithTheNext() throws Exception {
        // Abnahme #1077: Sonst scheiterte der Teil, und jeder Ersatzteil verlöre die nächste Berechnung.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            enrichments.background(background, key -> {
                if (key.pageId().equals("kaputt")) {
                    throw new IllegalStateException("Ablage nicht lesbar");
                }
                return Optional.ofNullable(cache.get(key));
            });
            enrichments.refresh(page("wiki", "kaputt", "Eins"));
            enrichments.refresh(page("wiki", "2", "Zwei"));

            runAll(held);

            JobStatus wiki = part(background, "wiki");
            assertThat(wiki.state()).isEqualTo(JobState.COMPLETED);
            assertThat(wiki.progress()).satisfies(progress -> {
                assertThat(progress.processed()).isEqualTo(2);
                assertThat(progress.failed()).isEqualTo(1);
                assertThat(progress.written()).isEqualTo(1);
            });
            assertThat(remote.calls).containsOnlyKeys("wiki/2");
            assertThat(jobs.events(wiki.id())).anySatisfy(event -> {
                assertThat(event.code()).isEqualTo(Enrichments.WORK_FAILED);
                assertThat(event.message()).contains("wiki", "IllegalStateException").doesNotContain("kaputt");
            });
        } finally {
            executor.shutdownNow();
        }
    }

    private Enrichments enrichments(Remote remote, ExecutorService executor, BackgroundJobs background) {
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
        enrichments.background(background, key -> Optional.ofNullable(cache.get(key)));
        return enrichments;
    }

    private PageSnapshot page(String sourceId, String pageId, String body) {
        PageSnapshot snapshot = text(sourceId, pageId, "1", body);
        cache.put(snapshot.key(), snapshot);
        return snapshot;
    }

    private static JobStatus part(BackgroundJobs background, String sourceId) {
        return background.parts(Enrichments.BACKGROUND_KIND).stream()
                .filter(part -> part.sourceId().equals(sourceId)).findFirst().orElseThrow();
    }

    private static Jobs jobs(java.util.concurrent.Executor executor, int slots) {
        return new Jobs(new InMemoryJobStore(), executor, new JobLimits(slots, 10, Duration.ofHours(1)), CLOCK);
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

    /** Wie ein Prozessor, der über das Netz rechnet; zählt seine Aufrufe je Seite. */
    private static final class Remote implements EnrichmentProcessor {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final java.util.Set<String> failing = ConcurrentHashMap.newKeySet();
        private final java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Läuft während der Berechnung dieser Seite, etwa ein neuer Zufluss. */
        private final Map<String, Runnable> during = new ConcurrentHashMap<>();

        @Override
        public String id() {
            return "remote";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public boolean background() {
            return true;
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EnrichmentOutput process(PageKey key, PageText text) {
            String page = key.sourceId() + "/" + key.pageId();
            calls.computeIfAbsent(page, ignored -> new AtomicInteger()).incrementAndGet();
            order.add(page);
            Runnable hook = during.remove(page);
            if (hook != null) {
                hook.run();
            }
            if (failing.contains(page)) {
                throw new IllegalStateException("Dienst nicht erreichbar");
            }
            return EnrichmentOutput.of("{}");
        }
    }
}
