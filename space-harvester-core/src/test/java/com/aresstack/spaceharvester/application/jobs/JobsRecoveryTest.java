package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Wiederanlauf nach Verbindungsabbruch und nachgeholtes „Nur Fehler neu versuchen“ (ADR 0025 Punkt 17). */
class JobsRecoveryTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobStore store = new InMemoryJobStore();
    private final Jobs jobs = new Jobs(store, new InMemoryJobEventStore(), sourceId -> Optional.empty(), executor,
            new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(30)), clock);
    private final List<Duration> delays = new ArrayList<>();
    private final List<Runnable> timed = new ArrayList<>();
    private final List<JobAction> performed = new ArrayList<>();
    private final JobTimer timer = (delay, task) -> {
        delays.add(delay);
        timed.add(task);
    };

    /** Wie die Anwendung: Fortsetzen über {@link Jobs#resume}, „Nur Fehler“ als neuer Lauf im selben Eintrag. */
    private void connect(JobTask next) {
        jobs.recovery((status, action) -> {
            performed.add(action);
            if (action == JobAction.RESUME) {
                jobs.resume(status.id(), status.owner());
            } else {
                jobs.rerun(status.id(), status.owner(), next);
            }
        }, timer);
    }

    private static JobTask offline() {
        return JobTask.blocking(context -> context.pause(PauseReason.CONNECTION_LOST));
    }

    private void fireLast() {
        timed.remove(timed.size() - 1).run();
    }

    @Test
    void aJobWithoutConnectionResumesByItselfWithDoublingDelaysUpToTheLongestAndNeverGivesUp() {
        connect(offline());
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", offline());
        executor.runNext();
        assertThat(jobs.status(job.id()).lifecycle().key()).isEqualTo("PAUSED_CONNECTION_LOST");
        assertThat(jobs.reconnectAt(job.id())).contains(clock.instant().plusSeconds(10));

        for (int i = 0; i < 10; i++) {
            fireLast();
            assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.RUNNING);
            executor.runNext();
        }

        assertThat(delays).extracting(Duration::toSeconds)
                .containsExactly(10L, 20L, 40L, 80L, 160L, 320L, 600L, 600L, 600L, 600L, 600L);
        assertThat(performed).containsOnly(JobAction.RESUME).hasSize(10);
    }

    @Test
    void aRunThatHeldAWhileStartsTheDelaysAgain() {
        connect(offline());
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", offline());
        executor.runNext();
        fireLast();
        executor.runNext();
        assertThat(delays).extracting(Duration::toSeconds).containsExactly(10L, 20L);
        AtomicBoolean held = new AtomicBoolean();
        JobTask longRun = JobTask.blocking(context -> {
            clock.advance(Jobs.RECONNECT_RESET_AFTER);
            held.set(true);
            context.pause(PauseReason.CONNECTION_LOST);
        });
        jobs.resume(job.id(), "client-a", previous -> longRun);
        executor.runNext();

        assertThat(held).isTrue();
        assertThat(delays).extracting(Duration::toSeconds).containsExactly(10L, 20L, 10L);
    }

    @Test
    void onlyALostConnectionResumesByItselfAndOnlyWhileNobodyActed() {
        connect(offline());
        CompletableFuture<Void> running = new CompletableFuture<>();
        JobStatus paused = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", context -> running);
        executor.runNext();
        jobs.pause(paused.id(), "client-a");
        running.completeExceptionally(new JobCancelledException(paused.id()));
        assertThat(jobs.status(paused.id()).lifecycle().key()).isEqualTo("PAUSED");
        assertThat(timed).isEmpty();

        JobStatus lost = jobs.submit(JobType.EXPORT, "wiki", "OPS", "client-a", offline());
        executor.runNext();
        // Der Benutzer setzt selbst fort und hält dann an: der alte Wiederanlauf ändert nichts mehr.
        Runnable stale = timed.remove(0);
        jobs.resume(lost.id(), "client-a", previous -> JobTask.blocking(context -> {
        }));
        executor.runNext();
        stale.run();
        assertThat(performed).isEmpty();
        assertThat(jobs.status(lost.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.reconnectAt(lost.id())).isEmpty();
    }

    @Test
    void aDeletedJobIsForgottenAndAFailedAttemptIsRetriedLater() {
        AtomicInteger calls = new AtomicInteger();
        jobs.recovery((status, action) -> {
            if (calls.incrementAndGet() == 1) {
                throw new JobCapacityExceededException(5);
            }
            jobs.resume(status.id(), status.owner());
        }, timer);
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", offline());
        executor.runNext();

        fireLast();
        assertThat(delays).extracting(Duration::toSeconds).containsExactly(10L, 20L);
        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.PAUSED);

        jobs.delete(job.id(), "client-a");
        fireLast();
        assertThat(calls).hasValue(1);
        assertThat(jobs.reconnectAt(job.id())).isEmpty();
    }

    @Test
    void jobsPausedForALostConnectionBeforeARestartResumeAfterTheFirstDelay() {
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", offline());
        executor.runNext();
        Jobs restarted = new Jobs(store, new InMemoryJobEventStore(), sourceId -> Optional.empty(), executor,
                new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(30)), clock);
        List<String> resumed = new ArrayList<>();
        restarted.recovery((status, action) -> resumed.add(status.id() + ":" + action), timer);

        assertThat(delays).extracting(Duration::toSeconds).containsExactly(10L);
        fireLast();
        assertThat(resumed).containsExactly(job.id() + ":RESUME");
    }

    @Test
    void retryingFailuresWhileRunningFollowsRightAfterThePassInTheSameEntry() {
        connect(JobTask.blocking(context -> context.processed(1)));
        CompletableFuture<Void> pass = new CompletableFuture<>();
        JobContext[] seen = new JobContext[1];
        JobStatus job = jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a", context -> {
            seen[0] = context;
            return pass;
        });
        executor.runNext();
        seen[0].discovered(3);
        seen[0].processed(3);
        seen[0].failed(2);
        assertThat(jobs.status(job.id()).lifecycle().actions()).first().isEqualTo(JobAction.RETRY_FAILED);

        jobs.requestRetryFailed(job.id(), "client-a");
        pass.complete(null);
        assertThat(jobs.status(job.id()).lifecycle().key()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(delays).containsExactly(Duration.ZERO);
        fireLast();

        assertThat(performed).containsExactly(JobAction.RETRY_FAILED);
        JobStatus again = jobs.status(job.id());
        assertThat(again.id()).isEqualTo(job.id());
        assertThat(again.state()).isEqualTo(JobState.RUNNING);
        executor.runNext();
        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void aRequestedRetryIsDroppedWhenThePassEndsWithoutFailures() {
        connect(JobTask.blocking(context -> {
        }));
        CompletableFuture<Void> pass = new CompletableFuture<>();
        JobStatus job = jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a", context -> pass);
        executor.runNext();
        jobs.requestRetryFailed(job.id(), "client-a");
        pass.complete(null);

        assertThat(timed).isEmpty();
        assertThat(jobs.status(job.id()).lifecycle().key()).isEqualTo("COMPLETED");
    }
}
