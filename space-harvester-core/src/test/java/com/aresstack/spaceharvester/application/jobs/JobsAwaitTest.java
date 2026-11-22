package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Warten auf das Ende eines Auftrags ohne Abfrage im Takt (ADR 0027). */
class JobsAwaitTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), executor, new JobLimits(1, 5, Duration.ofHours(1)),
            new MutableClock());

    @AfterEach
    void close() {
        jobs.close();
    }

    @Test
    void theWaiterWakesWhenTheJobEnds() throws Exception {
        String id = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { })).id();
        CompletableFuture<JobStatus> waited = waitFor(id, Duration.ofMinutes(5));

        executor.runNext();

        assertThat(waited.get(5, TimeUnit.SECONDS).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void closingWakesAWaiterOfAQueuedJobInsteadOfLettingItHangUntilTheDeadline() throws Exception {
        jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { }));
        String queued = jobs.submit(JobType.EXPORT, "wiki", "DOC", "client-a", JobTask.blocking(context -> { })).id();
        CompletableFuture<JobStatus> waited = waitFor(queued, Duration.ofMinutes(5));

        jobs.close();

        assertThat(waited.get(5, TimeUnit.SECONDS).state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void aJobStillRunningAtTheDeadlineIsReportedAsTimeout() {
        String id = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { })).id();

        assertThatThrownBy(() -> jobs.await(id, "client-a", Duration.ofMillis(50)))
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void anEndedJobComesBackAtOnce() throws Exception {
        String id = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { })).id();
        executor.runNext();

        assertThat(jobs.await(id, "client-a", Duration.ZERO).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void manyWaitersOnVirtualThreadsNeverHoldTheirCarriers() throws Exception {
        // Architekt 22.11.2026: Mit synchronized und wait() hielte jeder Wartende seinen Träger fest.
        int waiters = 400;
        String id = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { })).id();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(waiters);
        java.util.List<java.util.concurrent.Future<JobStatus>> waiting = new java.util.ArrayList<>();
        try (java.util.concurrent.ExecutorService virtual =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < waiters; i++) {
                waiting.add(virtual.submit(() -> {
                    entered.countDown();
                    return jobs.await(id, "client-a", Duration.ofMinutes(1));
                }));
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(virtual.submit(() -> "frei").get(10, TimeUnit.SECONDS)).isEqualTo("frei");

            executor.runNext();
            for (java.util.concurrent.Future<JobStatus> each : waiting) {
                assertThat(each.get(10, TimeUnit.SECONDS).state()).isEqualTo(JobState.COMPLETED);
            }
        }
    }

    private CompletableFuture<JobStatus> waitFor(String id, Duration timeout) throws InterruptedException {
        CompletableFuture<JobStatus> waited = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try {
                waited.complete(jobs.await(id, "client-a", timeout));
            } catch (Exception e) {
                waited.completeExceptionally(e);
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        // Erst weitermachen, wenn der Wartende wirklich wartet.
        while (waiter.getState() != Thread.State.TIMED_WAITING && !waited.isDone()) {
            Thread.onSpinWait();
        }
        return waited;
    }
}
