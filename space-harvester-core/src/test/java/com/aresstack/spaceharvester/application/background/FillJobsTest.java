package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Hintergrundfüllung als Hintergrundauftrag auf eigener Spur (Fundstelle 12); nur erfundene Quellen und Seiten. */
class FillJobsTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final BackgroundFill.Where DEV = new BackgroundFill.Where("wiki", "DEV");

    private final List<String> loaded = Collections.synchronizedList(new ArrayList<>());
    /** Seiten, deren Abruf begann (auch die noch gesperrten). */
    private final List<String> started = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    /** Lücken des Katalogs; was geladen ist, fällt heraus. */
    private final Set<String> gaps = Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), executor, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.systemUTC());
    private final BackgroundJobs background = new BackgroundJobs(jobs, "app");
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        gates.values().forEach(CountDownLatch::countDown);
        for (AutoCloseable resource : open.reversed()) {
            resource.close();
        }
        jobs.close();
        executor.shutdownNow();
    }

    private BackgroundFill fill() {
        return fill(2);
    }

    private BackgroundFill fill(int background) {
        AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
            started.add(key.pageId());
            CountDownLatch gate = gates.get(key.pageId());
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failing.contains(key.pageId())) {
                throw new com.aresstack.spaceharvester.application.sources.SourceAccessException("wiki",
                        com.aresstack.spaceharvester.application.sources.SourceAccessException.Reason.UNAVAILABLE,
                        "erfunden", null);
            }
            loaded.add(key.pageId());
            gaps.remove(key.pageId());
        }, new AcquisitionScheduler.Limits(4, background, 4, 50), null, Duration.ZERO);
        open.add(scheduler);
        BackgroundFill fill = new BackgroundFill(scheduler, BackgroundFill.FillQueue.inMemory(),
                new BackgroundFill.Catalog() {
                    @Override
                    public List<SyncBaselineStore.SpaceRef> spaces() {
                        return List.of(new SyncBaselineStore.SpaceRef("wiki", "DEV"));
                    }

                    @Override
                    public List<BackgroundFill.Gap> gaps(String sourceId, String spaceKey, int limit) {
                        synchronized (gaps) {
                            return gaps.stream().map(id -> new BackgroundFill.Gap(new PageKey("wiki", id),
                                    BackgroundFill.Kind.NOT_LOADED)).limit(limit).toList();
                        }
                    }
                }, APP, sourceId -> true, new BackgroundFill.Timing(Duration.ZERO, Duration.ofHours(1), 1000), true);
        open.add(fill);
        return fill;
    }

    private FillJobs fillJobs(BackgroundFill fill) {
        FillJobs fillJobs = new FillJobs(background, jobs);
        open.add(fillJobs);
        fillJobs.attach(fill, key -> Optional.of("DEV"));
        return fillJobs;
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 30 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private Optional<JobStatus> part(JobState state) {
        return background.parts(FillJobs.KIND).stream().filter(part -> part.state() == state).findFirst();
    }

    @Test
    void theFillOfASpaceIsOneVisiblePartThatCountsLoadedAndFailedPagesAndFailsWithAReason() throws Exception {
        gaps.addAll(List.of("1", "2"));
        failing.add("2");
        BackgroundFill fill = fill();
        fillJobs(fill);
        fill.start();

        eventually(() -> part(JobState.FAILED).isPresent());

        JobStatus done = part(JobState.FAILED).orElseThrow();
        assertThat(background.parts(FillJobs.KIND)).hasSize(1);
        assertThat(done.sourceId()).isEqualTo("wiki");
        assertThat(done.partitionKey()).isEqualTo("DEV");
        assertThat(done.parameter(BackgroundJobs.TITLE)).isEqualTo(FillJobs.TITLE);
        assertThat(done.parameter(Jobs.OWN_LANE)).isEqualTo("true");
        assertThat(done.progress().written()).isEqualTo(1);
        assertThat(done.progress().failed()).isEqualTo(1);
        assertThat(done.progress().discovered()).isEqualTo(done.progress().processed());
        assertThat(done.error().code()).isEqualTo(FillJobs.PAGES_MISSING);
        assertThat(loaded).containsExactly("1");
        // Der Grund kommt vom Ticket des Planers, nicht „unbekannt“ (Codex #1087).
        assertThat(jobs.events(done.id())).anySatisfy(event -> assertThat(event.message()).contains("UNAVAILABLE"));
    }

    private void gate(String... ids) {
        for (String id : ids) {
            gates.put(id, new CountDownLatch(1));
        }
    }

    /** Länger als die Ruhe nach einem Wecken ({@code BackgroundFill.SETTLE}): Eine Suche im Katalog wäre gelaufen. */
    private static void longerThanASearch() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(1500);
    }

    @Test
    void pausingLetsTheSpaceRestAndResumingLoadsOnInTheSamePart() throws Exception {
        // Abnahme #1087, Punkt 9: alle Seiten gesperrt; während der Pause beginnt nichts, es bleibt ein Teil.
        gaps.addAll(List.of("1", "2", "3"));
        gate("1", "2", "3");
        BackgroundFill fill = fill(1);
        FillJobs fillJobs = fillJobs(fill);
        fill.start();
        eventually(() -> started.contains("1") && part(JobState.RUNNING).isPresent());
        String groupId = part(JobState.RUNNING).orElseThrow().parameter(BackgroundJobs.GROUP);

        background.pause(groupId);
        eventually(() -> part(JobState.PAUSED).isPresent());
        assertThat(fillJobs.resting(DEV)).isTrue();
        // Nur der laufende Abruf bleibt eingetragen, damit sein Ende zählt.
        eventually(() -> fill.waiting(DEV) == 1);
        fill.wake();
        longerThanASearch();
        assertThat(started).containsExactly("1");
        assertThat(background.parts(FillJobs.KIND)).hasSize(1);

        background.resume(groupId);
        gates.values().forEach(CountDownLatch::countDown);

        eventually(() -> loaded.containsAll(List.of("1", "2", "3")));
        eventually(() -> part(JobState.COMPLETED).isPresent());
        assertThat(background.parts(FillJobs.KIND)).hasSize(1);
        assertThat(part(JobState.COMPLETED).orElseThrow().progress().written()).isEqualTo(3);
        eventually(() -> !fillJobs.resting(DEV));
    }

    @Test
    void cancellingLetsTheSpaceRestUntilTheNextSyncOfTheSpaceEvenAfterARestart() throws Exception {
        cancelThenSync("DEV");
    }

    @Test
    void cancellingLetsTheSpaceRestUntilTheNextSyncOfTheWholeSource() throws Exception {
        cancelThenSync(null);
    }

    private void cancelThenSync(String syncedSpace) throws Exception {
        gaps.addAll(List.of("1", "2"));
        gate("1");
        BackgroundFill fill = fill(1);
        FillJobs fillJobs = fillJobs(fill);
        fill.start();
        eventually(() -> started.contains("1") && part(JobState.RUNNING).isPresent());

        background.cancel(part(JobState.RUNNING).orElseThrow().parameter(BackgroundJobs.GROUP));
        eventually(() -> part(JobState.CANCELLED).isPresent());
        assertThat(fillJobs.resting(DEV)).isTrue();
        // Abgeleitet aus den Aufträgen: Ein neuer Start sieht dieselbe Ruhe.
        FillJobs restarted = new FillJobs(new BackgroundJobs(jobs, "app"), jobs);
        open.add(restarted);
        assertThat(restarted.resting(DEV)).isTrue();
        assertThat(restarted.resting(new BackgroundFill.Where("wiki", "OPS"))).isFalse();
        // Bis zum Abgleich lädt nichts, auch wenn die Füllung im Katalog sucht.
        gates.get("1").countDown();
        fill.wake();
        longerThanASearch();
        assertThat(started).containsExactly("1");
        assertThat(background.parts(FillJobs.KIND)).hasSize(1);

        jobs.submit(JobType.SYNC, "wiki", syncedSpace, "app", JobTask.blocking(context -> {
        }));

        eventually(() -> !fillJobs.resting(DEV));
        eventually(() -> loaded.containsAll(List.of("1", "2")));
    }

    @Test
    void cleaningUpASourceEndsItsPartAndALaterPartOfTheSpaceEndsToo() throws Exception {
        // Abnahme #1087, Punkt 2: „Daten bereinigen“ über die Füllung bereinigt auch ihren Spiegel.
        gaps.addAll(List.of("1", "2"));
        gate("1");
        BackgroundFill fill = fill(1);
        fillJobs(fill);
        fill.start();
        eventually(() -> started.contains("1") && part(JobState.RUNNING).isPresent());

        fill.removeSource("wiki");

        eventually(() -> part(JobState.COMPLETED).isPresent());
        assertThat(fill.waiting(DEV)).isZero();
        gates.get("1").countDown();
        gaps.clear();
        gaps.add("3");
        fill.wake();
        eventually(() -> loaded.contains("3"));
        eventually(() -> background.parts(FillJobs.KIND).size() == 2 && background.parts(FillJobs.KIND).stream()
                .allMatch(part -> part.state() == JobState.COMPLETED));
        assertThat(fill.waiting(DEV)).isZero();
    }

    @Test
    void deletingAPausedPartLetsTheSpaceLoadAgain() throws Exception {
        // Abnahme #1087, Punkt 4: sonst ruhte der Space bis zum Neustart.
        gaps.addAll(List.of("1", "2"));
        gate("1");
        BackgroundFill fill = fill(1);
        FillJobs fillJobs = fillJobs(fill);
        fill.start();
        eventually(() -> started.contains("1") && part(JobState.RUNNING).isPresent());
        background.pause(part(JobState.RUNNING).orElseThrow().parameter(BackgroundJobs.GROUP));
        eventually(() -> part(JobState.PAUSED).isPresent());
        assertThat(fillJobs.resting(DEV)).isTrue();

        jobs.delete(part(JobState.PAUSED).orElseThrow().id(), "app");
        gates.get("1").countDown();

        eventually(() -> !fillJobs.resting(DEV));
        eventually(() -> loaded.containsAll(List.of("1", "2")));
    }

    @Test
    void twoLastPagesEndingAtOnceAreBothCountedAndThePartEndsOnce() throws Exception {
        // Abnahme #1087, Punkt 9: zwei gleichzeitige letzte Abschlüsse.
        gaps.addAll(List.of("1", "2"));
        CountDownLatch together = new CountDownLatch(1);
        gates.put("1", together);
        gates.put("2", together);
        BackgroundFill fill = fill(2);
        fillJobs(fill);
        fill.start();
        eventually(() -> started.containsAll(List.of("1", "2")));

        together.countDown();

        eventually(() -> part(JobState.COMPLETED).isPresent());
        assertThat(background.parts(FillJobs.KIND)).hasSize(1);
        JobStatus done = part(JobState.COMPLETED).orElseThrow();
        assertThat(done.progress().written()).isEqualTo(2);
        assertThat(done.progress().processed()).isEqualTo(2);
    }

    @Test
    void anOwnLanePartNeverTakesAPlaceFromOtherBackgroundParts() {
        // Abnahme Fundstelle 12, Punkt 1: zwei Plätze, also einer für Hintergrund; die Füllung nimmt ihn nie.
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs slots = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(2, 10, Duration.ofHours(1)),
                Clock.systemUTC());
        try {
            BackgroundJobs own = new BackgroundJobs(slots, "app");
            own.register(new BackgroundKind(FillJobs.KIND, part -> context -> new CompletableFuture<>(), () -> true,
                    false, true));
            own.register(new BackgroundKind("other", part -> JobTask.blocking(context -> {
            })));

            BackgroundGroup filling = own.start(FillJobs.KIND, FillJobs.TITLE,
                    List.of(new BackgroundPart.Draft("wiki", "DEV", Map.of())));
            BackgroundGroup first = own.start("other", "Andere", List.of(new BackgroundPart.Draft("wiki", "A",
                    Map.of())));
            BackgroundGroup second = own.start("other", "Andere", List.of(new BackgroundPart.Draft("wiki", "B",
                    Map.of())));

            assertThat(own.group(filling.groupId()).orElseThrow().parts()).extracting(JobStatus::state)
                    .containsOnly(JobState.RUNNING);
            assertThat(own.group(first.groupId()).orElseThrow().parts()).extracting(JobStatus::state)
                    .containsOnly(JobState.RUNNING);
            // Der eine Hintergrundplatz ist belegt: wie bisher wartet der nächste.
            assertThat(own.group(second.groupId()).orElseThrow().parts()).extracting(JobStatus::state)
                    .containsOnly(JobState.QUEUED);
        } finally {
            slots.close();
        }
    }

    @Test
    void anOwnLaneIsNoSwitchForAnyKind() {
        assertThatThrownBy(() -> new BackgroundKind("pages", part -> JobTask.blocking(context -> {
        }), () -> true, false, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
