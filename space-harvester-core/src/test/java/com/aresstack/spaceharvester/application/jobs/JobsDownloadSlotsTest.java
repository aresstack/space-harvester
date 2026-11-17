package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Eigene Plätze für Vorbereitung und Download von Bildern und Anhängen (Angelo 11.11.2026). */
class JobsDownloadSlotsTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), new InMemoryJobEventStore(), sourceId -> Optional.empty(),
            executor, new JobLimits(2, 10, Duration.ofHours(1), Duration.ofDays(30)).withParallel(1, 0), clock);
    private final List<Thread> workers = new ArrayList<>();
    private final CountDownLatch finish = new CountDownLatch(1);

    @AfterEach
    void stop() throws InterruptedException {
        finish.countDown();
        for (Thread worker : workers) {
            worker.join(5_000);
        }
    }

    /** Ein Download, der vorbereitet, auf seinen Platz wartet und dann lädt, bis der Test ihn beendet. */
    private JobTask download() {
        return JobTask.blocking(context -> {
            context.discovered(3);
            context.awaitDownloadSlot();
            context.processed(1);
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void runInBackground() {
        Thread worker = new Thread(executor::runNext);
        workers.add(worker);
        worker.start();
    }

    private JobStatus await(String jobId, Predicate<JobStatus> condition) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            JobStatus status = jobs.status(jobId);
            if (condition.test(status)) {
                return status;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Zustand nicht erreicht: " + jobs.status(jobId));
    }

    @Test
    void withZeroDownloadsJobsArePreparedButNothingIsLoadedUntilASlotOpens() throws InterruptedException {
        JobStatus images = jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a", download());
        assertThat(jobs.status(images.id()).lifecycle().key()).isEqualTo("PREPARING");
        runInBackground();

        JobStatus prepared = await(images.id(), status -> status.stage() == JobStage.PREPARED);
        assertThat(prepared.lifecycle().key()).isEqualTo("PREPARED");
        assertThat(prepared.lifecycle().actions())
                .containsExactly(JobAction.START_NOW, JobAction.CANCEL, JobAction.DELETE);
        assertThat(prepared.progress().discovered()).isEqualTo(3);
        assertThat(prepared.progress().processed()).isZero();

        jobs.parallelDownloads(1);
        JobStatus loading = await(images.id(), status -> status.stage() == JobStage.DOWNLOADING);
        assertThat(loading.lifecycle().key()).isEqualTo("RUNNING");
        finish.countDown();
        await(images.id(), status -> status.state() == JobState.COMPLETED);
    }

    @Test
    void aFinishedPreparationFreesItsSlotForTheNextOne() throws InterruptedException {
        JobStatus first = jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a", download());
        JobStatus second = jobs.submit(JobType.ATTACHMENTS, "wiki", "DEV", "client-a", download());
        // Ein Platz für Vorbereitungen: der zweite wartet, ein Export läuft trotzdem.
        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.QUEUED);
        JobStatus export = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { }));
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.RUNNING);

        runInBackground();
        await(first.id(), status -> status.stage() == JobStage.PREPARED);
        await(second.id(), status -> status.stage() == JobStage.PREPARING);
    }

    @Test
    void startNowGivesAPreparedJobTheNextSlotAndCancelEndsTheWait() throws InterruptedException {
        jobs.parallelPreparations(2);
        JobStatus first = jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a", download());
        JobStatus second = jobs.submit(JobType.ASSETS, "wiki", "OPS", "client-a", download());
        runInBackground();
        runInBackground();
        await(first.id(), status -> status.stage() == JobStage.PREPARED);
        await(second.id(), status -> status.stage() == JobStage.PREPARED);

        jobs.prioritize(second.id(), "client-a");
        jobs.parallelDownloads(1);
        await(second.id(), status -> status.stage() == JobStage.DOWNLOADING);
        assertThat(jobs.status(first.id()).stage()).isEqualTo(JobStage.PREPARED);

        jobs.cancel(first.id(), "client-a");
        await(first.id(), status -> status.state() == JobState.CANCELLED);
    }

    @Test
    void preparingLongerThanTheRunTimeLimitDoesNotFailTheJob() throws InterruptedException {
        JobStatus job = jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            clock.advance(Duration.ofHours(2));
            context.checkpoint();
            context.awaitDownloadSlot();
            context.checkpoint();
        }));
        runInBackground();
        await(job.id(), status -> status.stage() == JobStage.PREPARED);

        jobs.parallelDownloads(1);

        await(job.id(), status -> status.state() == JobState.COMPLETED);
    }

    /**
     * #944: Exporte aus der Quelle (Confluence-Metadaten, Analytics) laden auf den eingestellten Download-Plätzen, nicht
     * auf den beiden Plätzen für Abgleiche und Exporte; ein frei werdender Platz geht an den nächsten, nach Erfolg,
     * Fehler und Abbruch.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 4})
    void exportsFromTheSourceLoadOnTheConfiguredDownloadSlots(int downloads) throws InterruptedException {
        Jobs lanes = new Jobs(new InMemoryJobStore(), new InMemoryJobEventStore(), sourceId -> Optional.empty(),
                executor, new JobLimits(2, 20, Duration.ofHours(1), Duration.ofDays(30)).withParallel(8, downloads),
                clock);
        java.util.Map<String, CountDownLatch> ends = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Set<String> fails = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            CountDownLatch end = new CountDownLatch(1);
            JobType type = i % 2 == 0 ? JobType.METADATA_EXPORT : JobType.ANALYTICS_EXPORT;
            JobStatus job = lanes.submit(type, "wiki", "S" + i, "client-a", JobTask.blocking(context -> {
                context.discovered(10);
                context.awaitDownloadSlot();
                try {
                    ends.get(context.jobId()).await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                context.checkpoint();
                if (fails.contains(context.jobId())) {
                    throw new IllegalStateException("Quelle antwortet falsch");
                }
            }));
            ends.put(job.id(), end);
            ids.add(job.id());
        }
        for (int i = 0; i < 8; i++) {
            runInBackground();
        }

        awaitLanes(lanes, ids, downloads, 8 - downloads);

        // Erfolg, Fehler und Abbruch geben je einen Platz frei; der nächste vorbereitete lädt.
        String done = loading(lanes, ids);
        ends.get(done).countDown();
        awaitState(lanes, done, JobState.COMPLETED);
        awaitLanes(lanes, ids, Math.min(downloads, 7), Math.max(0, 7 - downloads));

        String failing = loading(lanes, ids);
        fails.add(failing);
        ends.get(failing).countDown();
        awaitState(lanes, failing, JobState.FAILED);
        awaitLanes(lanes, ids, Math.min(downloads, 6), Math.max(0, 6 - downloads));

        String cancelled = loading(lanes, ids);
        lanes.cancel(cancelled, "client-a");
        ends.get(cancelled).countDown();
        awaitState(lanes, cancelled, JobState.CANCELLED);
        awaitLanes(lanes, ids, Math.min(downloads, 5), Math.max(0, 5 - downloads));
        // Die Plätze für Abgleiche und Exporte bleiben frei: ein Export startet sofort.
        JobStatus export = lanes.submit(JobType.EXPORT, "andere", "X", "client-b", JobTask.blocking(context -> { }));
        assertThat(lanes.status(export.id()).state()).isEqualTo(JobState.RUNNING);

        ends.values().forEach(CountDownLatch::countDown);
    }

    private static String loading(Jobs lanes, List<String> ids) {
        return ids.stream().filter(id -> lanes.status(id).stage() == JobStage.DOWNLOADING).findFirst().orElseThrow();
    }

    private static void awaitLanes(Jobs lanes, List<String> ids, int loading, int waiting) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            long nowLoading = ids.stream().filter(id -> lanes.status(id).stage() == JobStage.DOWNLOADING).count();
            long nowWaiting = ids.stream().filter(id -> lanes.status(id).stage() == JobStage.PREPARED).count();
            if (nowLoading == loading && nowWaiting == waiting) {
                assertThat(lanes.downloadingNow()).isEqualTo(loading);
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Erwartet " + loading + " ladend und " + waiting + " wartend: "
                + ids.stream().map(id -> String.valueOf(lanes.status(id).stage())).toList());
    }

    private static void awaitState(Jobs lanes, String id, JobState state) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (lanes.status(id).state() == state) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Zustand " + state + " nicht erreicht: " + lanes.status(id));
    }

    @Test
    void theSettingsAreChecked() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.parallelDownloads(-1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.parallelPreparations(0))
                .isInstanceOf(IllegalArgumentException.class);
        jobs.parallelDownloads(JobLimits.MAX_PARALLEL_DOWNLOADS);
        assertThat(jobs.parallelDownloads()).isEqualTo(64);
        assertThat(new JobLimits(2, 20, Duration.ofHours(6)).parallelDownloads()).isEqualTo(4);
        assertThat(new JobLimits(2, 20, Duration.ofHours(6)).parallelPreparations()).isEqualTo(2);
    }
}
