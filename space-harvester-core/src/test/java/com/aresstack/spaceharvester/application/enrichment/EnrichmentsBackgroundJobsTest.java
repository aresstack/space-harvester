package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
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
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path queueDirectory;

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
            // Angelo 22.11.2026: Der Fehler steht rot mit Grund in der Diagnose, ohne Meldungstext des Dienstes.
            assertThat(jobs.events(part(background, "wiki").id())).anySatisfy(event -> {
                assertThat(event.severity()).isEqualTo(JobEvent.Severity.ERROR);
                assertThat(event.code()).isEqualTo(Enrichments.WORK_FAILED);
                assertThat(event.message()).contains(remote.id(), "Seite 2", "wiki", "IllegalStateException")
                        .doesNotContain("Dienst nicht erreichbar");
                assertThat(event.detail()).isNull();
            });
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
    void aForcedRecomputationReplacesTheSameUnforcedWorkInsteadOfComputingTwice() throws Exception {
        // Abnahme #1078: Eine erzwungene Berechnung ersetzt die gleiche ungezwungene an ihrer Stelle.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("x", "1", "Vorne"));
            PageSnapshot first = page("wiki", "1", "Eins");
            enrichments.refresh(first);
            enrichments.refresh(page("wiki", "2", "Zwei"));
            assertThat(enrichments.recomputeInBackground(first, List.of("remote"))).isEqualTo(1);
            enrichments.refresh(first);

            runAll(held);

            assertThat(remote.calls.get("wiki/1").get()).isEqualTo(1);
            assertThat(remote.order).containsExactly("x/1", "wiki/1", "wiki/2");
            assertThat(part(background, "wiki").progress().processed()).isEqualTo(2);
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
                assertThat(event.severity()).isEqualTo(JobEvent.Severity.ERROR);
                // Die Seite als Verweis, nie der Meldungstext (Abnahme 22.11.2026).
                assertThat(event.message()).contains("wiki", "Seite kaputt", "IllegalStateException")
                        .doesNotContain("Ablage nicht lesbar");
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aTlsFailureNamesOnlyTheExceptionClassNeverItsMessage() {
        // Codex #1103: Der TLS-Befund trug die Meldung der Ausnahme.
        RuntimeException cause = new IllegalStateException("Aufruf gescheitert",
                new javax.net.ssl.SSLHandshakeException("Geheimer Seitentext im Zertifikat"));
        JobEvent.Draft draft = Enrichments.failure(new Enrichments.Work("remote", new PageKey("wiki", "7"), false),
                "wiki", cause);

        assertThat(draft.tlsFinding()).isEqualTo("SSLHandshakeException");
        assertThat(draft.message()).contains("Seite 7", "SSLHandshakeException").doesNotContain("Geheimer");
    }

    @Test
    void retryingComputesOnlyTheFailedPagesAgainInTheSameEntry() throws Exception {
        // Angelo 22.11.2026: ↻ wiederholt im Hintergrund das Gescheiterte wie im Vordergrund.
        Remote remote = new Remote();
        remote.failing.add("wiki/2");
        remote.failing.add("wiki/3");
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("wiki", "1", "Eins"));
            enrichments.refresh(page("wiki", "2", "Zwei"));
            enrichments.refresh(page("wiki", "3", "Drei"));
            runAll(held);
            JobStatus first = part(background, "wiki");
            assertThat(first.lifecycle().allows(com.aresstack.spaceharvester.application.jobs.JobAction.RETRY_FAILED))
                    .isTrue();

            remote.failing.clear();
            // Die KI-Freigabe gilt je Seite neu: Seite 3 ist inzwischen ausgenommen.
            remote.blocked.add("wiki/3");
            JobStatus again = background.retryFailed(first.id());
            runAll(held);

            assertThat(again.id()).isEqualTo(first.id());
            JobStatus retried = part(background, "wiki");
            assertThat(background.parts(Enrichments.BACKGROUND_KIND)).hasSize(1);
            assertThat(retried.state()).isEqualTo(JobState.COMPLETED);
            assertThat(retried.progress().written()).isEqualTo(1);
            assertThat(retried.progress().failed()).isZero();
            assertThat(remote.calls.get("wiki/1").get()).isEqualTo(1);
            assertThat(remote.calls.get("wiki/2").get()).isEqualTo(2);
            assertThat(remote.calls.get("wiki/3").get()).as("ohne Freigabe nichts an den Dienst").isEqualTo(1);
            assertThatThrownBy(() -> background.retryFailed(first.id()))
                    .isInstanceOf(com.aresstack.spaceharvester.application.background.NothingToRetryException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anOrdinaryResumeStillCountsTheEarlierFailureSoRetryStaysOffered() throws Exception {
        // Codex #1109: Fortsetzen beginnt bei null; das Gescheiterte von vorher bliebe sonst unsichtbar.
        Remote remote = new Remote();
        remote.failing.add("wiki/1");
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("wiki", "1", "Eins"));
            enrichments.refresh(page("wiki", "2", "Zwei"));
            String groupId = part(background, "wiki").parameter(BackgroundJobs.GROUP);
            remote.during.put("wiki/1", () -> background.pause(groupId));
            runAll(held);
            assertThat(part(background, "wiki").state()).isEqualTo(JobState.PAUSED);

            background.resume(groupId);
            runAll(held);

            JobStatus done = part(background, "wiki");
            assertThat(done.state()).isEqualTo(JobState.COMPLETED);
            assertThat(remote.calls).containsOnlyKeys("wiki/1", "wiki/2");
            assertThat(done.progress().failed()).isEqualTo(1);
            assertThat(done.lifecycle().allows(com.aresstack.spaceharvester.application.jobs.JobAction.RETRY_FAILED))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retryingAnOlderPartWaitsBehindTheOpenPartsOfTheQueue() throws Exception {
        // Codex #1109: Die Teile laufen hintereinander, auch ein wiederholter älterer.
        Remote remote = new Remote();
        remote.failing.add("wiki/1");
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        try {
            Enrichments enrichments = enrichments(remote, executor, background);
            enrichments.refresh(page("wiki", "1", "Eins"));
            runAll(held);
            JobStatus older = part(background, "wiki");
            enrichments.refresh(page("ops", "1", "Drei"));
            JobStatus newer = part(background, "ops");
            remote.failing.clear();

            background.retryFailed(older.id());

            assertThat(background.part(older.id()).orElseThrow().parameter(Jobs.AFTER)).isEqualTo(newer.id());
            runAll(held);
            assertThat(remote.order).containsExactly("wiki/1", "ops/1", "wiki/1");
            assertThat(background.part(older.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anInterruptedPartContinuesAfterTheRestartWithWhatWasLeftInTheSameEntry() throws Exception {
        // Angelo 22.11.2026: „Abgebrochen 1150/1895“ nach dem Beenden; jetzt läuft der Rest nach dem Neustart weiter.
        Remote remote = new Remote();
        remote.failing.add("wiki/1");
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs before = new Jobs(jobStore, held::add, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
        BackgroundJobs backgroundBefore = new BackgroundJobs(before, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            Enrichments first = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            first.background(backgroundBefore, key -> Optional.ofNullable(cache.get(key)), queue);
            for (int page = 1; page <= 4; page++) {
                first.refresh(page("wiki", String.valueOf(page), "Text " + page));
            }
            // Mitten in Seite 2 wird das Programm beendet, wie Harvester#close: erst unterbrechen, dann schließen.
            remote.during.put("wiki/2", () -> {
                before.interruptBackground();
                try {
                    first.closeBackground(Duration.ZERO);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            runAll(held);
            JobStatus interrupted = part(backgroundBefore, "wiki");
            assertThat(interrupted.state()).isEqualTo(JobState.FAILED);
            assertThat(queue.all()).singleElement().satisfies(saved -> {
                assertThat(saved.work()).extracting(EnrichmentQueueStore.Entry::pageId).containsExactly("3", "4");
                assertThat(saved.failed()).extracting(EnrichmentQueueStore.Entry::pageId).containsExactly("1");
            });

            // Neustart: dieselben Ablagen, neue Auftragsverwaltung.
            remote.failing.clear();
            Jobs after = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs backgroundAfter = new BackgroundJobs(after, "app");
            Enrichments second = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            second.background(backgroundAfter, key -> Optional.ofNullable(cache.get(key)), queue);
            assertThat(backgroundAfter.part(interrupted.id()).orElseThrow().state()).as("nicht abgeschlossen")
                    .isEqualTo(JobState.FAILED);
            backgroundAfter.resumeInterrupted();

            JobStatus resumed = backgroundAfter.part(interrupted.id()).orElseThrow();
            assertThat(resumed.state()).isEqualTo(JobState.COMPLETED);
            assertThat(backgroundAfter.parts(Enrichments.BACKGROUND_KIND)).hasSize(1);
            assertThat(remote.calls.get("wiki/2").get()).isEqualTo(1);
            assertThat(remote.calls.get("wiki/3").get()).isEqualTo(1);
            assertThat(remote.calls.get("wiki/4").get()).isEqualTo(1);
            assertThat(remote.calls.get("wiki/1").get()).as("das Gescheiterte erst mit ↻").isEqualTo(1);
            // Der Fehler von vorher zählt weiter, damit ↻ auch nach dem Neustart angeboten wird.
            assertThat(resumed.progress().failed()).isEqualTo(1);
            assertThat(resumed.lifecycle().allows(com.aresstack.spaceharvester.application.jobs.JobAction.RETRY_FAILED))
                    .isTrue();

            backgroundAfter.retryFailed(interrupted.id());
            assertThat(remote.calls.get("wiki/1").get()).isEqualTo(2);
            assertThat(backgroundAfter.part(interrupted.id()).orElseThrow().progress().failed()).isZero();
            assertThat(queue.all()).as("nichts mehr offen, nichts mehr abgelegt").isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aPausedPartStaysPausedAfterTheRestartAndKeepsItsWork() throws Exception {
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs before = new Jobs(jobStore, held::add, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
        BackgroundJobs backgroundBefore = new BackgroundJobs(before, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            Enrichments first = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            first.background(backgroundBefore, key -> Optional.ofNullable(cache.get(key)), queue);
            first.refresh(page("x", "1", "Vorne"));
            first.refresh(page("wiki", "1", "Eins"));
            String groupId = part(backgroundBefore, "wiki").parameter(BackgroundJobs.GROUP);
            backgroundBefore.pause(groupId);
            first.refresh(page("wiki", "2", "Zwei"));
            before.interruptBackground();
            first.closeBackground(Duration.ZERO);

            Jobs after = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs backgroundAfter = new BackgroundJobs(after, "app");
            Enrichments second = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            second.background(backgroundAfter, key -> Optional.ofNullable(cache.get(key)), queue);
            backgroundAfter.resumeInterrupted();

            assertThat(part(backgroundAfter, "wiki").state()).as("vom Benutzer angehalten bleibt angehalten")
                    .isEqualTo(JobState.PAUSED);
            assertThat(remote.calls).containsOnlyKeys("x/1");
            backgroundAfter.resume(groupId);

            assertThat(remote.calls).containsOnlyKeys("x/1", "wiki/1", "wiki/2");
            assertThat(part(backgroundAfter, "wiki").state()).isEqualTo(JobState.COMPLETED);
            assertThat(queue.all()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aPartAUserStartedWaitsYellowForTheUserAfterTheRestart() throws Exception {
        // Abnahme 22.11.2026: Mit dem Benutzer im Speicher fehlen seine Rechte; nie von selbst weiter.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs before = new Jobs(jobStore, held::add, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
        BackgroundJobs backgroundBefore = new BackgroundJobs(before, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            Enrichments first = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            first.background(backgroundBefore, key -> Optional.ofNullable(cache.get(key)), queue);
            page("wiki", "1", "Eins");
            com.aresstack.spaceharvester.application.background.BackgroundGroup started = backgroundBefore.start(
                    Enrichments.BACKGROUND_KIND, Enrichments.BACKGROUND_TITLE, List.of(
                            new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", null,
                                    Map.of("owner", "anna"))));
            JobStatus mine = started.parts().getFirst();
            queue.save(new EnrichmentQueueStore.Saved(started.groupId(), "wiki", mine.id(),
                    List.of(new EnrichmentQueueStore.Entry("remote", "1", false)), List.of()));

            // Absturz: Der Teil wartete noch; der Neustart findet ihn unterbrochen.
            Jobs after = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs backgroundAfter = new BackgroundJobs(after, "app");
            Enrichments second = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            second.background(backgroundAfter, key -> Optional.ofNullable(cache.get(key)), queue);
            backgroundAfter.resumeInterrupted();

            JobStatus waiting = backgroundAfter.part(mine.id()).orElseThrow();
            assertThat(waiting.state()).isEqualTo(JobState.PAUSED);
            assertThat(waiting.pauseReason())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.RESUME_BY_HAND);
            assertThat(after.events(mine.id())).anySatisfy(event -> {
                assertThat(event.code()).isEqualTo(Enrichments.RESUME_BY_HAND);
                assertThat(event.severity()).isEqualTo(JobEvent.Severity.WARNING);
            });
            assertThat(remote.calls).isEmpty();
            assertThat(queue.all()).singleElement().satisfies(saved -> assertThat(saved.work()).hasSize(1));

            // Von Hand fortgesetzt rechnet er.
            backgroundAfter.resume(started.groupId());
            assertThat(remote.calls).containsOnlyKeys("wiki/1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void purgingASourceForgetsItsQueueAlsoTheSavedOneAndItsPartComputesNothing() throws Exception {
        Remote remote = new Remote();
        remote.failing.add("wiki/9");
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            enrichments.background(background, key -> Optional.ofNullable(cache.get(key)), queue);
            enrichments.refresh(page("wiki", "9", "Neun"));
            runAll(held);
            String failedGroup = part(background, "wiki").parameter(BackgroundJobs.GROUP);
            enrichments.refresh(page("wiki", "1", "Eins"));
            enrichments.refresh(page("ops", "1", "Drei"));
            assertThat(queue.all()).extracting(EnrichmentQueueStore.Saved::sourceId)
                    .containsExactlyInAnyOrder("wiki", "wiki", "ops");

            enrichments.forgetQueued("wiki");

            assertThat(queue.all()).extracting(EnrichmentQueueStore.Saved::sourceId).containsExactly("ops");
            runAll(held);
            assertThat(remote.calls).containsOnlyKeys("wiki/9", "ops/1");
            assertThat(queue.all()).isEmpty();
            assertThatThrownBy(() -> background.retryFailed(background.group(failedGroup).orElseThrow().parts()
                    .getFirst().id()))
                    .isInstanceOf(com.aresstack.spaceharvester.application.background.NothingToRetryException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aComputationInterruptedByClosingWaitsAgainWithoutARedEntry() throws Exception {
        // Abnahme #1112: Die laufende Seite bleibt im abgelegten Stand, und das Beenden macht sie nicht rot.
        Remote remote = new Remote();
        remote.failing.add("wiki/2");
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = new Jobs(jobStore, held::add, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        java.util.List<EnrichmentQueueStore.Saved> whileComputing = new java.util.ArrayList<>();
        try {
            Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            enrichments.background(background, key -> Optional.ofNullable(cache.get(key)), queue);
            for (int page = 1; page <= 3; page++) {
                enrichments.refresh(page("wiki", String.valueOf(page), "Text " + page));
            }
            // Wie Harvester#close mitten in Seite 2; der KI-Aufruf endet danach mit einer Ausnahme (unterbrochen).
            remote.during.put("wiki/2", () -> {
                jobs.interruptBackground();
                try {
                    enrichments.closeBackground(Duration.ZERO);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                whileComputing.addAll(queue.all());
            });
            runAll(held);

            assertThat(whileComputing).singleElement().satisfies(saved -> assertThat(saved.work())
                    .extracting(EnrichmentQueueStore.Entry::pageId).containsExactly("2", "3"));
            JobStatus interrupted = part(background, "wiki");
            assertThat(BackgroundJobs.interrupted(interrupted)).isTrue();
            assertThat(jobs.events(interrupted.id())).noneMatch(event -> Enrichments.WORK_FAILED.equals(event.code()));
            assertThat(queue.all()).singleElement().satisfies(saved -> {
                assertThat(saved.work()).containsExactly(new EnrichmentQueueStore.Entry("remote", "2", true),
                        new EnrichmentQueueStore.Entry("remote", "3", false));
                assertThat(saved.failed()).isEmpty();
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void theWorkOfAFailedPartGoesToANewPartOnlyTogetherWithTheResume() throws Exception {
        // Abnahme #1112: Vorher ist das offene Löschen einer Quelle noch nicht angemeldet (Jobs#sourceHold).
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            BackgroundJobs backgroundBefore = restart(jobStore, null, remote, executor, queue);
            page("wiki", "1", "Eins");
            page("ops", "1", "Drei");
            JobStatus automatic = failedPart(backgroundBefore, jobStore, queue, "wiki", Map.of());
            JobStatus byUser = failedPart(backgroundBefore, jobStore, queue, "ops", Map.of("owner", "anna"));

            Jobs after = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs backgroundAfter = restart(jobStore, after, remote, executor, queue);

            assertThat(backgroundAfter.parts(Enrichments.BACKGROUND_KIND)).as("noch kein neuer Teil")
                    .extracting(JobStatus::id).containsExactlyInAnyOrder(automatic.id(), byUser.id());
            assertThat(remote.calls).isEmpty();
            assertThat(queue.all()).as("die alten Dateien bleiben, bis übergeben ist")
                    .allSatisfy(saved -> assertThat(saved.work()).hasSize(1)).hasSize(2);

            backgroundAfter.resumeInterrupted();

            assertThat(remote.calls).containsOnlyKeys("wiki/1");
            assertThat(backgroundAfter.part(automatic.id()).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
            JobStatus handedOver = backgroundAfter.parts(Enrichments.BACKGROUND_KIND).stream()
                    .filter(part -> part.sourceId().equals("ops") && !part.id().equals(byUser.id()))
                    .findFirst().orElseThrow();
            // Von einem Benutzer gestartet: Der neue Teil gehört ihm und hält gelb an, bis ein Mensch fortsetzt.
            assertThat(BackgroundJobs.startedByUser(handedOver)).isTrue();
            assertThat(handedOver.state()).isEqualTo(JobState.PAUSED);
            assertThat(handedOver.pauseReason())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.RESUME_BY_HAND);
            assertThat(after.events(handedOver.id())).anySatisfy(event -> {
                assertThat(event.code()).isEqualTo(Enrichments.RESUME_BY_HAND);
                assertThat(event.severity()).isEqualTo(JobEvent.Severity.WARNING);
            });
            assertThat(queue.all()).singleElement().satisfies(saved -> assertThat(saved.partId())
                    .isEqualTo(handedOver.id()));

            backgroundAfter.resume(handedOver.parameter(BackgroundJobs.GROUP));
            assertThat(remote.calls).containsOnlyKeys("wiki/1", "ops/1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aStartThatEndsBeforeTheHandOverKeepsTheWorkForTheNextStart() throws Exception {
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            BackgroundJobs first = restart(jobStore, null, remote, executor, queue);
            page("wiki", "1", "Eins");
            JobStatus failed = failedPart(first, jobStore, queue, "wiki", Map.of());

            // Zweiter Start endet vor dem Fortsetzen (etwa ein Fehler weiter hinten im Start).
            restart(jobStore, null, remote, executor, queue);
            assertThat(queue.all()).singleElement().satisfies(saved -> assertThat(saved.work()).hasSize(1));

            Jobs third = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs background = restart(jobStore, third, remote, executor, queue);
            assertThat(background.part(failed.id()).orElseThrow().state()).isEqualTo(JobState.FAILED);
            background.resumeInterrupted();

            assertThat(remote.calls).containsOnlyKeys("wiki/1");
            assertThat(queue.all()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aHandedOverPartOfAUserNeverRunsByItselfEvenAfterASecondRestart() throws Exception {
        // Nachabnahme #1112: Vor seinem ersten Lauf unterbrochen, darf ihn auch resumeInterrupted nicht rechnen lassen.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            BackgroundJobs first = restart(jobStore, null, remote, executor, queue);
            page("ops", "1", "Drei");
            failedPart(first, jobStore, queue, "ops", Map.of("owner", "anna"));

            // Zweiter Start: übergeben, aber der neue Teil kommt nicht mehr dran, das Programm wird beendet.
            java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
            Jobs second = new Jobs(jobStore, held::add, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs backgroundSecond = restart(jobStore, second, remote, executor, queue);
            backgroundSecond.resumeInterrupted();
            JobStatus handedOver = backgroundSecond.parts(Enrichments.BACKGROUND_KIND).stream()
                    .filter(part -> !part.state().terminal()).findFirst().orElseThrow(() -> new AssertionError(backgroundSecond.parts(Enrichments.BACKGROUND_KIND)));
            second.interruptBackground();
            held.clear();

            Jobs third = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            BackgroundJobs backgroundThird = restart(jobStore, third, remote, executor, queue);
            backgroundThird.resumeInterrupted();

            JobStatus waiting = backgroundThird.part(handedOver.id()).orElseThrow();
            assertThat(remote.calls).isEmpty();
            assertThat(waiting.state()).isEqualTo(JobState.PAUSED);
            assertThat(waiting.pauseReason())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.RESUME_BY_HAND);
            backgroundThird.resume(handedOver.parameter(BackgroundJobs.GROUP));
            assertThat(remote.calls).containsOnlyKeys("ops/1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void purgedOrWithdrawnSourcesGetNoHandOverAfterTheRestart() throws Exception {
        // Nachabnahme #1112: resumeWithdrawals läuft vor resumeInterrupted.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            BackgroundJobs first = restart(jobStore, null, remote, executor, queue);
            page("wiki", "1", "Eins");
            page("ops", "1", "Drei");
            failedPart(first, jobStore, queue, "wiki", Map.of());
            failedPart(first, jobStore, queue, "ops", Map.of());

            Jobs after = new Jobs(jobStore, Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)), CLOCK);
            // „ops“ wird noch gelöscht.
            after.sourceHold(sourceId -> "ops".equals(sourceId) ? "loeschen-1" : null);
            Enrichments second = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            BackgroundJobs background = new BackgroundJobs(after, "app");
            second.background(background, key -> Optional.ofNullable(cache.get(key)), queue);
            // „wiki“ ist schon bereinigt.
            second.forgetQueued("wiki");
            background.resumeInterrupted();

            assertThat(remote.calls).isEmpty();
            assertThat(background.parts(Enrichments.BACKGROUND_KIND)).as("kein neuer Teil").hasSize(2)
                    .allSatisfy(part -> assertThat(part.state().terminal()).isTrue());
            assertThat(queue.all()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Ein Start: neue Auftragsverwaltung (ohne {@code jobs} eine, die nichts ausführt) über derselben Ablage. */
    private BackgroundJobs restart(InMemoryJobStore jobStore, Jobs jobs, Remote remote, ExecutorService executor,
                                   EnrichmentQueueStore queue) {
        Jobs used = jobs != null ? jobs : new Jobs(jobStore, task -> { }, new JobLimits(4, 10, Duration.ofHours(1)),
                CLOCK);
        BackgroundJobs background = new BackgroundJobs(used, "app");
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
        enrichments.background(background, key -> Optional.ofNullable(cache.get(key)), queue);
        return background;
    }

    @Test
    void aFailureAfterPurgingTheSourceRecordsNothingForIt() throws Exception {
        // Abnahme #1112: Sonst schriebe der Fehlschlag die Datei der bereinigten Quelle neu, und ↻ böte sie an.
        Remote remote = new Remote();
        remote.failing.add("wiki/1");
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        try {
            Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            enrichments.background(background, key -> Optional.ofNullable(cache.get(key)), queue);
            enrichments.refresh(page("wiki", "1", "Eins"));
            enrichments.refresh(page("wiki", "2", "Zwei"));
            remote.during.put("wiki/1", () -> enrichments.forgetQueued("wiki"));

            runAll(held);

            assertThat(remote.calls).containsOnlyKeys("wiki/1");
            assertThat(queue.all()).isEmpty();
            assertThatThrownBy(() -> background.retryFailed(part(background, "wiki").id()))
                    .isInstanceOf(com.aresstack.spaceharvester.application.background.NothingToRetryException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aFailureIsSavedAtOnceSoACrashKeepsItForRetry() throws Exception {
        // Nachabnahme #1112: Das FAILED-Ergebnis gilt schon als aktuell; nach einem Absturz fiele die Seite sonst weg.
        Remote remote = new Remote();
        remote.failing.add("wiki/1");
        ExecutorService executor = Enrichments.background(10);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = jobs(held::add, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        java.util.List<EnrichmentQueueStore.Saved> afterTheFailure = new java.util.ArrayList<>();
        try {
            Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            enrichments.background(background, key -> Optional.ofNullable(cache.get(key)), queue);
            enrichments.refresh(page("wiki", "1", "Eins"));
            enrichments.refresh(page("wiki", "2", "Zwei"));
            remote.during.put("wiki/2", () -> afterTheFailure.addAll(queue.all()));

            runAll(held);

            assertThat(afterTheFailure).singleElement().satisfies(saved -> {
                assertThat(saved.failed()).extracting(EnrichmentQueueStore.Entry::pageId).containsExactly("1");
                assertThat(saved.work()).extracting(EnrichmentQueueStore.Entry::pageId).containsExactly("2");
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void purgingThroughTheRealStoresHoldsTheSourcesPartAndForgetsItsQueue() throws Exception {
        // Abnahme #1112: über SourceDataPurge/SourceDataStores wie im Harvester, nicht über forgetQueued direkt.
        Remote remote = new Remote();
        ExecutorService executor = Enrichments.background(10);
        ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "test-jobs");
            thread.setDaemon(true);
            return thread;
        });
        Jobs jobs = jobs(pool, 4);
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue queue =
                new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(queueDirectory);
        java.util.concurrent.CountDownLatch deleting = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, executor);
            enrichments.background(background, key -> Optional.ofNullable(cache.get(key)), queue);
            java.util.List<com.aresstack.spaceharvester.application.sources.SourceDataPurge.Store> stores =
                    new java.util.ArrayList<>();
            stores.add(new com.aresstack.spaceharvester.application.sources.SourceDataPurge.Store("Seiten", id -> {
                deleting.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            stores.addAll(com.aresstack.spaceharvester.core.SourceDataStores.builder()
                    .enrichmentQueue(enrichments::forgetQueued).build());
            com.aresstack.spaceharvester.application.sources.SourceDataPurge purge =
                    new com.aresstack.spaceharvester.application.sources.SourceDataPurge(jobs, stores, id -> true);
            JobStatus withdrawal = purge.startWithdrawal("wiki", "app",
                    com.aresstack.spaceharvester.application.sources.SourceDataPurge.REMOVED);
            assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();

            enrichments.refresh(page("wiki", "1", "Eins"));
            JobStatus heldPart = part(background, "wiki");
            assertThat(queue.all()).extracting(EnrichmentQueueStore.Saved::sourceId).containsExactly("wiki");
            assertThat(background.part(heldPart.id()).orElseThrow().state()).as("hinter dem Löschen gehalten")
                    .isEqualTo(JobState.QUEUED);
            assertThat(remote.calls).isEmpty();

            release.countDown();
            assertThat(jobs.await(withdrawal.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(jobs.await(heldPart.id(), "app", Duration.ofSeconds(30)).state().terminal()).isTrue();
            assertThat(remote.calls).as("nach dem Löschen nichts mehr zu tun").isEmpty();
            assertThat(queue.all()).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
            pool.shutdownNow();
        }
    }

    /** Ein Teil, der vor dem Neustart gescheitert ist (nicht unterbrochen), mit abgelegter Arbeit. */
    private static JobStatus failedPart(BackgroundJobs background, InMemoryJobStore jobStore,
                                        EnrichmentQueueStore queue, String sourceId, Map<String, String> parameters) {
        com.aresstack.spaceharvester.application.background.BackgroundGroup started = background.start(
                Enrichments.BACKGROUND_KIND, Enrichments.BACKGROUND_TITLE, List.of(
                        new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft(sourceId, null,
                                parameters)));
        JobStatus queued = jobStore.find(started.parts().getFirst().id()).orElseThrow();
        JobStatus failed = new JobStatus(queued.id(), queued.type(), queued.sourceId(), queued.partitionKey(),
                queued.owner(), JobState.FAILED, queued.progress(),
                new com.aresstack.spaceharvester.application.jobs.JobError("BOOM", "Erfundener Fehler"), false,
                queued.createdAt(), queued.createdAt(), queued.createdAt(), queued.rootPageId(), queued.rootTitle(),
                queued.parameters(), false, false, null);
        jobStore.save(failed);
        queue.save(new EnrichmentQueueStore.Saved(started.groupId(), sourceId, queued.id(),
                List.of(new EnrichmentQueueStore.Entry("remote", "1", false)), List.of()));
        return failed;
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
        /** Seiten ohne KI-Freigabe. */
        private final java.util.Set<String> blocked = ConcurrentHashMap.newKeySet();

        @Override
        public boolean appliesTo(PageKey key) {
            return !blocked.contains(key.sourceId() + "/" + key.pageId());
        }

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
