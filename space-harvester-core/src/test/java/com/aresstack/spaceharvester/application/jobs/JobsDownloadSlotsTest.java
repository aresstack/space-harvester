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
