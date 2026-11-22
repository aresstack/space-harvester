package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.aresstack.spaceharvester.application.background.BackgroundGroup;
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
        // Ein Platz: der Teil wartet und lässt sich anhalten, bevor er läuft.
        Jobs jobs = jobs(Runnable::run, 1);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("wiki", "1", "Eins"));
            String groupId = background.groups().getFirst().groupId();
            background.pause(groupId);
            enrichments.refresh(page("wiki", "2", "Zwei"));
            assertThat(background.groups()).as("der angehaltene Teil nimmt die neue Seite mit").hasSize(1);

            jobs.parallelPreparations(2);
            background.resume(groupId);
            awaitUntil(() -> background.group(groupId).orElseThrow().finished());

            assertThat(remote.calls).containsOnlyKeys("wiki/1", "wiki/2");
            assertThat(background.group(groupId).orElseThrow().parts().getFirst().state())
                    .isEqualTo(JobState.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellingDropsTheWaitingWorkSaysSoAndTheNextPageStartsANewPart() throws Exception {
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        Jobs jobs = jobs(Runnable::run, 1);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("wiki", "1", "Eins"));
            BackgroundGroup first = background.groups().getFirst();
            background.cancel(first.groupId());
            String partId = first.parts().getFirst().id();
            assertThat(jobs.events(partId)).anySatisfy(event -> assertThat(event.message())
                    .endsWith("; " + Enrichments.CANCEL_NOTE));

            jobs.parallelPreparations(2);
            enrichments.refresh(page("wiki", "2", "Zwei"));
            awaitUntil(() -> remote.calls.containsKey("wiki/2"));

            assertThat(background.groups()).hasSize(2);
            assertThat(remote.calls).as("die abgebrochene Seite holt der nächste Abgleich nach")
                    .containsOnlyKeys("wiki/2");
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
        Jobs jobs = jobs(Runnable::run, 1);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                for (int i = 0; i <= Enrichments.FORCED_IN_BACKGROUND; i++) {
                    assertThat(enrichments.recomputeInBackground(page("wiki", "p" + i, "Text " + i),
                            List.of("remote"))).isEqualTo(1);
                }
            });
            assertThat(background.groups()).singleElement().satisfies(group -> assertThat(group.parts())
                    .singleElement().satisfies(part -> assertThat(part.state()).isEqualTo(JobState.QUEUED)));

            jobs.parallelPreparations(2);
            // Ein Platz mehr gibt den wartenden Teil frei.
            enrichments.refresh(page("ops", "1", "Anstoß"));
            awaitUntil(() -> remote.calls.size() == Enrichments.FORCED_IN_BACKGROUND + 2);
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
        Jobs jobs = jobs(Runnable::run, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            PageSnapshot ok = page("wiki", "1", "Eins");
            PageSnapshot broken = page("wiki", "2", "Zwei");
            // Beide warten, bevor der Teil läuft: ein Platz, dann zwei.
            jobs.parallelPreparations(1);
            enrichments.refresh(ok);
            enrichments.refresh(broken);
            jobs.parallelPreparations(2);
            awaitUntil(() -> part(background, "wiki").state() == JobState.COMPLETED);

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
            if (failing.contains(page)) {
                throw new IllegalStateException("Dienst nicht erreichbar");
            }
            return EnrichmentOutput.of("{}");
        }
    }
}
