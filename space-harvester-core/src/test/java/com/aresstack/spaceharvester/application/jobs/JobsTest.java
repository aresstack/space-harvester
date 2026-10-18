package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JobsTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobStore store = new InMemoryJobStore();

    private Jobs jobs(int maxConcurrent, int maxQueued) {
        return new Jobs(store, executor, new JobLimits(maxConcurrent, maxQueued, Duration.ofHours(1)), clock);
    }

    @Test
    void submitReturnsQueuedJobAtOnceAndRunsItLater() {
        Jobs jobs = jobs(1, 5);
        FakePageReader reader = new FakePageReader(3);
        List<String> seen = new ArrayList<>();

        JobStatus submitted = jobs.submit(JobType.EXPORT, "confluence", "DEV", "client-a", reader.exportTask(seen::add));

        // Gestartet, aber noch nichts ausgeführt: der Aufrufer wartet nicht auf die Arbeit.
        assertThat(seen).isEmpty();
        assertThat(jobs.status(submitted.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(store.find(submitted.id())).get().extracting(JobStatus::state).isEqualTo(JobState.RUNNING);

        executor.runNext();

        JobStatus done = jobs.status(submitted.id());
        assertThat(seen).containsExactly("p1", "p2", "p3");
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 3, 0));
        assertThat(done.error()).isNull();
        assertThat(done.type()).isEqualTo(JobType.EXPORT);
        assertThat(done.sourceId()).isEqualTo("confluence");
        assertThat(done.partitionKey()).isEqualTo("DEV");
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.finishedAt()).isNotNull();
        assertThat(store.find(submitted.id())).contains(done);
    }

    @Test
    void submittedStatusIsQueued() {
        Jobs jobs = jobs(1, 5);
        jobs.submit(JobType.SYNC, "confluence", "DEV", "client-a", JobTask.blocking(context -> { }));

        JobStatus second = jobs.submit(JobType.HARVEST, "confluence", null, "client-a", JobTask.blocking(context -> { }));

        assertThat(second.state()).isEqualTo(JobState.QUEUED);
        assertThat(second.startedAt()).isNull();
        assertThat(second.partitionKey()).isNull();
    }

    @Test
    void runsAtMostMaxConcurrentJobsAndStartsNextWhenOneEnds() {
        Jobs jobs = jobs(1, 5);
        JobStatus first = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        JobStatus second = jobs.submit(JobType.EXPORT, "confluence", "B", "client-a", JobTask.blocking(context -> { }));

        assertThat(executor.pending()).isEqualTo(1);
        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.QUEUED);

        executor.runNext();

        assertThat(jobs.status(first.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.RUNNING);
        executor.runNext();
        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void followUpJobsNeverTakeTheLastSlotAndWaitBehindExports() {
        Jobs jobs = jobs(2, 10);
        JobStatus images = jobs.submit(JobType.ASSETS, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        JobStatus enrichment = jobs.submit(JobType.ENRICHMENT, "confluence", "A", "client-a",
                JobTask.blocking(context -> { }));
        JobStatus export = jobs.submit(JobType.EXPORT, "confluence", "B", "client-a", JobTask.blocking(context -> { }));

        // Ein Platz für die Bilder, der letzte bleibt dem Export; die Anreicherung wartet.
        assertThat(jobs.status(images.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(enrichment.id()).state()).isEqualTo(JobState.QUEUED);
        JobStatus sync = jobs.submit(JobType.SYNC, "confluence", "C", "client-a", JobTask.blocking(context -> { }));

        executor.runNext();
        executor.runNext();

        // Der später eingereihte Abgleich kommt vor der früher eingereihten Anreicherung.
        assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(enrichment.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void followUpsAreAcceptedWhileTheirTriggerStillHoldsTheOnlySlot() {
        Jobs jobs = new Jobs(store, executor, new JobLimits(1, 0, Duration.ofHours(1), Duration.ofDays(1), 1), clock);
        JobStatus export = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));

        JobStatus images = jobs.submit(JobType.ASSETS, "confluence", "A", "client-a", JobTask.blocking(context -> { }));

        assertThatThrownBy(() -> jobs.submit(JobType.EXPORT, "confluence", "B", "client-a",
                JobTask.blocking(context -> { }))).isInstanceOf(JobCapacityExceededException.class);
        assertThatThrownBy(() -> jobs.submit(JobType.ENRICHMENT, "confluence", "A", "client-a",
                JobTask.blocking(context -> { }))).isInstanceOf(JobCapacityExceededException.class);
        executor.runNext();
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.status(images.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void followUpsOfOneClientCannotTakeTheWholeFollowUpPool() {
        Jobs jobs = new Jobs(store, executor, new JobLimits(1, 4, Duration.ofHours(1), Duration.ofDays(1), 1), clock);
        jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        jobs.submit(JobType.ENRICHMENT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        jobs.submit(JobType.ASSETS, "confluence", "A", "client-a", JobTask.blocking(context -> { }));

        assertThatThrownBy(() -> jobs.submit(JobType.ASSETS, "confluence", "A", "client-a",
                JobTask.blocking(context -> { }))).isInstanceOf(JobCapacityExceededException.class);
        assertThat(jobs.submit(JobType.ASSETS, "confluence", "B", "client-b", JobTask.blocking(context -> { }))
                .state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void asynchronousTaskHoldsItsSlotUntilItsStageCompletes() {
        Jobs jobs = jobs(1, 5);
        CompletableFuture<Void> transport = new CompletableFuture<>();
        JobStatus async = jobs.submit(JobType.HARVEST, "confluence", "A", "client-a", context -> transport);
        JobStatus next = jobs.submit(JobType.HARVEST, "confluence", "B", "client-a", JobTask.blocking(context -> { }));

        executor.runNext();

        assertThat(jobs.status(async.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(next.id()).state()).isEqualTo(JobState.QUEUED);
        assertThat(executor.pending()).isZero();

        transport.complete(null);

        assertThat(jobs.status(async.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.status(next.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void rejectsJobsBeyondCapacity() {
        Jobs jobs = jobs(1, 1);
        jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        jobs.submit(JobType.EXPORT, "confluence", "B", "client-a", JobTask.blocking(context -> { }));

        assertThatThrownBy(() -> jobs.submit(JobType.EXPORT, "confluence", "C", "client-a", JobTask.blocking(context -> { })))
                .isInstanceOf(JobCapacityExceededException.class)
                .hasMessageContaining("höchstens 2");
    }

    @Test
    void oneClientCannotTakeAllSlots() {
        Jobs jobs = new Jobs(store, executor, new JobLimits(1, 5, Duration.ofHours(1), Duration.ofDays(1), 2), clock);
        jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        jobs.submit(JobType.EXPORT, "confluence", "B", "client-a", JobTask.blocking(context -> { }));

        assertThatThrownBy(() -> jobs.submit(JobType.EXPORT, "confluence", "C", "client-a", JobTask.blocking(context -> { })))
                .isInstanceOf(JobCapacityExceededException.class)
                .hasMessageContaining("dieses Clients");
        assertThat(jobs.submit(JobType.EXPORT, "confluence", "C", "client-b", JobTask.blocking(context -> { })).state())
                .isEqualTo(JobState.QUEUED);
        assertThat(jobs.submit(JobType.SYNC, "confluence", "D", null, JobTask.blocking(context -> { })).state())
                .isEqualTo(JobState.QUEUED);

        executor.runNext();
        assertThat(jobs.submit(JobType.EXPORT, "confluence", "E", "client-a", JobTask.blocking(context -> { })).state())
                .isEqualTo(JobState.QUEUED);
    }

    @Test
    void cancellingAQueuedJobEndsItWithoutRunningIt() {
        Jobs jobs = jobs(1, 5);
        jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        AtomicBoolean ran = new AtomicBoolean();
        JobStatus queued = jobs.submit(JobType.EXPORT, "confluence", "B", "client-a", JobTask.blocking(context -> ran.set(true)));

        JobStatus cancelled = jobs.cancel(queued.id());

        assertThat(cancelled.state()).isEqualTo(JobState.CANCELLED);
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled.cancelRequested()).isFalse();
        executor.runNext();
        assertThat(executor.pending()).isZero();
        assertThat(ran).isFalse();
        assertThat(jobs.status(queued.id()).state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void cancellingARunningJobStopsItAtTheNextCheckpoint() {
        Jobs jobs = jobs(1, 5);
        FakePageReader reader = new FakePageReader(10);
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<JobStatus> whileRunning = new AtomicReference<>();
        List<String> seen = new ArrayList<>();
        id.set(jobs.submit(JobType.EXPORT, "confluence", "DEV", "client-a", reader.exportTask(pageId -> {
            seen.add(pageId);
            if (pageId.equals("p3")) {
                // Abbruch kommt während der Verarbeitung von p3, wie von einer zweiten Anfrage.
                whileRunning.set(jobs.cancel(id.get()));
            }
        })).id());

        executor.runNext();

        assertThat(whileRunning.get().state()).isEqualTo(JobState.RUNNING);
        assertThat(whileRunning.get().cancelRequested()).isTrue();
        JobStatus status = jobs.status(id.get());
        assertThat(status.state()).isEqualTo(JobState.CANCELLED);
        assertThat(status.error()).isNull();
        assertThat(status.cancelRequested()).isFalse();
        assertThat(seen).containsExactly("p1", "p2", "p3");
        assertThat(status.progress()).isEqualTo(new JobProgress(10, 3, 3, 0));
    }

    @Test
    void cancellingAFinishedJobChangesNothing() {
        Jobs jobs = jobs(1, 5);
        JobStatus job = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        executor.runNext();

        assertThat(jobs.cancel(job.id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void namedFailureIsReportedWithCodeAndMessage() {
        Jobs jobs = jobs(1, 5);
        JobStatus job = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> {
            context.failed(1);
            throw new JobFailedException("ARTIFACT_INCOMPLETE", "Seite 7 fehlt, Artefakt nicht veröffentlicht");
        }));

        executor.runNext();

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error()).isEqualTo(
                new JobError("ARTIFACT_INCOMPLETE", "Seite 7 fehlt, Artefakt nicht veröffentlicht"));
        assertThat(status.progress().failed()).isEqualTo(1);
    }

    @Test
    void sourceFailureKeepsItsReason() {
        Jobs jobs = jobs(1, 5);
        JobStatus job = jobs.submit(JobType.HARVEST, "confluence", "A", "client-a", JobTask.blocking(context -> {
            throw new SourceAccessException("confluence", SourceAccessException.Reason.UNAVAILABLE,
                    "Quelle confluence nicht erreichbar", null);
        }));

        executor.runNext();

        JobError error = jobs.status(job.id()).error();
        assertThat(error.code()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(error.message()).contains("UNAVAILABLE").doesNotContain("Quelle confluence");
    }

    @Test
    void sourceFailureNeverPublishesTransportMessages() {
        Jobs jobs = jobs(1, 5);
        JobStatus job = jobs.submit(JobType.HARVEST, "confluence", "A", "client-a", JobTask.blocking(context -> {
            throw new SourceAccessException("confluence", SourceAccessException.Reason.UNAVAILABLE,
                    "Connection refused: https://wiki.intern.example:8443/rest/api/content?cql=space=DEV", null);
        }));

        executor.runNext();

        assertThat(jobs.status(job.id()).error().message()).doesNotContain("wiki.intern", "cql", "8443");
    }

    @Test
    void unexpectedFailureNeverExposesItsMessage() {
        Jobs jobs = jobs(1, 5);
        JobStatus job = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> {
            throw new IllegalStateException("GET https://wiki.example/rest?token=secret-pat failed");
        }));

        executor.runNext();

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("UNEXPECTED");
        assertThat(status.error().message()).contains("IllegalStateException").doesNotContain("secret", "wiki");
    }

    @Test
    void failedAsynchronousStageIsUnwrapped() {
        Jobs jobs = jobs(1, 5);
        JobStatus job = jobs.submit(JobType.HARVEST, "confluence", "A", "client-a", context -> CompletableFuture
                .runAsync(() -> { }, Runnable::run)
                .thenRun(() -> {
                    throw new JobFailedException("REMOTE", "Fehler im Transport");
                }));

        executor.runNext();

        assertThat(jobs.status(job.id()).error()).isEqualTo(new JobError("REMOTE", "Fehler im Transport"));
    }

    @Test
    void exceedingTheRunTimeFailsWithTimeoutAtTheNextCheckpoint() {
        Jobs jobs = new Jobs(store, executor, new JobLimits(1, 5, Duration.ofMinutes(10)), clock);
        JobStatus job = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> {
            context.checkpoint();
            clock.advance(Duration.ofMinutes(11));
            context.checkpoint();
            throw new AssertionError("nicht erreicht");
        }));

        executor.runNext();

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("TIMEOUT");
    }

    @Test
    void jobsLeftOpenByAPreviousRunAreMarkedInterruptedOnStart() {
        Jobs before = jobs(1, 5);
        JobStatus running = before.submit(JobType.SYNC, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        JobStatus queued = before.submit(JobType.SYNC, "confluence", "B", "client-a", JobTask.blocking(context -> { }));
        JobStatus finished = new JobStatus("done", JobType.EXPORT, "confluence", "C", null, JobState.COMPLETED,
                JobProgress.NONE, null, false, clock.instant(), clock.instant(), clock.instant());
        store.save(finished);

        // Neustart: dieselbe Ablage, neue Verwaltung; die alten Aufgaben laufen nie.
        Jobs after = new Jobs(store, new ManualExecutor(), new JobLimits(1, 5, Duration.ofHours(1)), clock);

        for (JobStatus open : List.of(running, queued)) {
            JobStatus status = after.status(open.id());
            assertThat(status.state()).isEqualTo(JobState.FAILED);
            assertThat(status.error().code()).isEqualTo("INTERRUPTED");
            assertThat(status.finishedAt()).isNotNull();
        }
        assertThat(after.status(finished.id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void closeCancelsQueuedJobsRequestsCancelOfRunningOnesAndRejectsNewOnes() {
        Jobs jobs = jobs(1, 5);
        FakePageReader reader = new FakePageReader(2);
        JobStatus running = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", reader.exportTask(pageId -> { }));
        JobStatus queued = jobs.submit(JobType.EXPORT, "confluence", "B", "client-a", JobTask.blocking(context -> { }));

        jobs.close();

        assertThat(jobs.status(queued.id()).state()).isEqualTo(JobState.CANCELLED);
        assertThat(jobs.status(running.id()).cancelRequested()).isTrue();
        assertThatThrownBy(() -> jobs.submit(JobType.EXPORT, "confluence", "C", "client-a", JobTask.blocking(context -> { })))
                .isInstanceOf(IllegalStateException.class);
        executor.runNext();
        assertThat(jobs.status(running.id()).state()).isEqualTo(JobState.CANCELLED);
        assertThat(jobs.status(running.id()).progress().processed()).isZero();
    }

    @Test
    void onlyTheOwnerSeesAndCancelsItsJob() {
        Jobs jobs = jobs(1, 5);
        JobStatus job = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));

        assertThat(jobs.status(job.id(), "client-a").owner()).isEqualTo("client-a");
        // Ein fremder Auftrag verhält sich wie ein unbekannter.
        assertThatThrownBy(() -> jobs.status(job.id(), "client-b")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> jobs.cancel(job.id(), "client-b")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> jobs.status(job.id(), null)).isInstanceOf(UnknownJobException.class);
        assertThat(jobs.status(job.id()).cancelRequested()).isFalse();

        assertThat(jobs.cancel(job.id(), "client-a").cancelRequested()).isTrue();
    }

    @Test
    void finishedJobsAreRemovedAfterTheirRetention() {
        Jobs jobs = new Jobs(store, executor, new JobLimits(1, 5, Duration.ofHours(1), Duration.ofDays(7)), clock);
        JobStatus old = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> { }));
        executor.runNext();
        clock.advance(Duration.ofDays(3));
        JobStatus recent = jobs.submit(JobType.EXPORT, "confluence", "B", "client-a",
                JobTask.blocking(context -> { }));
        executor.runNext();
        JobStatus running = jobs.submit(JobType.EXPORT, "confluence", "C", "client-a",
                context -> new java.util.concurrent.CompletableFuture<>());
        executor.runNext();

        clock.advance(Duration.ofDays(4).plusSeconds(1));

        assertThat(jobs.purgeExpired()).isEqualTo(1);
        assertThatThrownBy(() -> jobs.status(old.id())).isInstanceOf(UnknownJobException.class);
        assertThat(jobs.status(recent.id()).state()).isEqualTo(JobState.COMPLETED);
        // Laufende Aufträge bleiben, egal wie alt.
        assertThat(jobs.status(running.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void expiredJobsAreRemovedOnStartAndOnSubmit() {
        Jobs before = new Jobs(store, executor, new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(1)), clock);
        JobStatus done = before.submit(JobType.SYNC, "confluence", "A", null, JobTask.blocking(context -> { }));
        executor.runNext();
        clock.advance(Duration.ofDays(2));

        new Jobs(store, executor, new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(1)), clock);
        assertThat(store.find(done.id())).isEmpty();

        JobStatus other = before.submit(JobType.SYNC, "confluence", "B", null, JobTask.blocking(context -> { }));
        executor.runNext();
        clock.advance(Duration.ofDays(2));
        before.submit(JobType.SYNC, "confluence", "C", null, JobTask.blocking(context -> { }));
        assertThat(store.find(other.id())).isEmpty();
    }

    @Test
    void unknownJob() {
        Jobs jobs = jobs(1, 5);

        assertThatThrownBy(() -> jobs.status("nope")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> jobs.cancel("nope")).isInstanceOf(UnknownJobException.class);
    }

    @Test
    void runsOnRealThreadsWithoutBlockingTheCaller() throws Exception {
        ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Jobs jobs = new Jobs(store, threads, new JobLimits(2, 5, Duration.ofHours(1)), clock);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            JobStatus job = jobs.submit(JobType.EXPORT, "confluence", "A", "client-a", JobTask.blocking(context -> {
                context.discovered(1);
                release.await();
                context.processed(1);
            }));
            JobStatus other = jobs.submit(JobType.EXPORT, "confluence", "B", "client-a", context -> {
                done.countDown();
                return CompletableFuture.completedFuture(null);
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.RUNNING);
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!jobs.status(job.id()).state().terminal() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.COMPLETED);
            assertThat(jobs.status(job.id()).progress()).isEqualTo(new JobProgress(1, 1, 0, 0));
            assertThat(jobs.status(other.id()).state()).isIn(JobState.RUNNING, JobState.COMPLETED);
        } finally {
            threads.shutdownNow();
        }
    }
}
