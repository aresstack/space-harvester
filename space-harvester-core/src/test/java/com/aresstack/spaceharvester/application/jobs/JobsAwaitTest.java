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
        java.util.List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (java.util.concurrent.ExecutorService virtual =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < waiters; i++) {
                    waiting.add(virtual.submit(() -> {
                        threads.add(Thread.currentThread());
                        entered.countDown();
                        return jobs.await(id, "client-a", Duration.ofMinutes(1));
                    }));
                }
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                // Erst wenn alle wirklich parken, sagt der freie Träger etwas (Abnahme #1133).
                assertThat(allParked(threads)).isTrue();
                assertThat(virtual.submit(() -> "frei").get(10, TimeUnit.SECONDS)).isEqualTo("frei");

                executor.runNext();
                for (java.util.concurrent.Future<JobStatus> each : waiting) {
                    assertThat(each.get(10, TimeUnit.SECONDS).state()).isEqualTo(JobState.COMPLETED);
                }
            } finally {
                // Scheitert eine Frist, wartet close() des Executors sonst auf die Wartenden.
                jobs.close();
            }
        }
    }

    @Test
    void manyCallersOfCancelSourceOnVirtualThreadsNeverHoldTheirCarriers() throws Exception {
        // Architekt, Abnahme #1133: Wer auf das Ende der Aufträge einer Quelle wartet, parkt ohne Träger.
        Jobs threaded = new Jobs(new InMemoryJobStore(), command -> Thread.ofPlatform().daemon().start(command),
                new JobLimits(1, 5, Duration.ofHours(1)), new MutableClock());
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        threaded.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            started.countDown();
            release.await();
        }));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        runManyWaiters(() -> threaded.cancelSource("wiki", Duration.ofMinutes(1)), release, threaded, Boolean.TRUE);
    }

    @Test
    void manyCallersOfAwaitSettledOnVirtualThreadsNeverHoldTheirCarriers() throws Exception {
        Jobs threaded = new Jobs(new InMemoryJobStore(), command -> Thread.ofPlatform().daemon().start(command),
                new JobLimits(1, 5, Duration.ofHours(1)), new MutableClock());
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        String id = threaded.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            started.countDown();
            release.await();
        })).id();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        runManyWaiters(() -> threaded.awaitSettled(id, "client-a", Duration.ofMinutes(1)).state(), release, threaded,
                JobState.COMPLETED);
    }

    /** 400 Wartende auf virtuellen Threads: alle parken, ein Träger bleibt frei, das Freigeben weckt alle. */
    private static <T> void runManyWaiters(java.util.concurrent.Callable<T> wait,
                                           java.util.concurrent.CountDownLatch release, Jobs threaded, T expected)
            throws Exception {
        int waiters = 400;
        java.util.List<java.util.concurrent.Future<T>> waiting = new java.util.ArrayList<>();
        java.util.List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (java.util.concurrent.ExecutorService virtual =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < waiters; i++) {
                    waiting.add(virtual.submit(() -> {
                        threads.add(Thread.currentThread());
                        return wait.call();
                    }));
                }
                assertThat(allParkedOnceStarted(threads, waiters)).isTrue();
                assertThat(virtual.submit(() -> "frei").get(10, TimeUnit.SECONDS)).isEqualTo("frei");

                release.countDown();
                for (java.util.concurrent.Future<T> each : waiting) {
                    assertThat(each.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
                }
            } finally {
                release.countDown();
                threaded.close();
            }
        }
    }

    /** Wie {@link #allParked}, wartet aber erst, bis alle {@code count} Threads gestartet sind. */
    private static boolean allParkedOnceStarted(java.util.List<Thread> threads, int count) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (threads.size() < count && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        return threads.size() == count && allParked(threads);
    }

    /** Ob alle Threads binnen zehn Sekunden parken (WAITING oder TIMED_WAITING); ohne Schlafen. */
    static boolean allParked(java.util.List<Thread> threads) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (threads.stream().allMatch(thread -> thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
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
