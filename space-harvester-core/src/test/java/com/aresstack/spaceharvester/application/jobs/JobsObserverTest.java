package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.observe.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Auftragsmeldungen statt Abfragen (ADR 0027): Zustand, Zähler und Phase kommen gebündelt beim Auftraggeber an,
 * Löschungen und Diagnoseereignisse ebenso; ein hängender Schritt wird nach {@link Jobs#STOP_GRACE} auch ohne Abfrage
 * abgekoppelt.
 */
class JobsObserverTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobStore store = new InMemoryJobStore();
    private final Recorder mine = new Recorder();
    private final Recorder foreign = new Recorder();
    private Jobs jobs;

    @AfterEach
    void close() {
        if (jobs != null) {
            jobs.close();
        }
    }

    @Test
    void stateCountersAndPhaseArriveAtTheOwnerWithoutAnyQuery() throws InterruptedException {
        jobs = new Jobs(store, executor, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        jobs.subscribe("client-a", mine);
        jobs.subscribe("client-b", foreign);
        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Thread worker = new Thread(executor::runNext);

        String id = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            context.phase("Export", "Seiten lesen");
            context.discovered(3);
            context.processed(2);
            counted.countDown();
            await(proceed);
            context.processed(1);
        })).id();
        assertThat(mine.next(status -> status.id().equals(id) && status.state() == JobState.RUNNING)).isNotNull();
        worker.start();
        assertThat(counted.await(5, TimeUnit.SECONDS)).isTrue();

        JobStatus counting = mine.next(status -> status.progress().processed() == 2);
        assertThat(counting.progress().discovered()).isEqualTo(3);
        assertThat(counting.state()).isEqualTo(JobState.RUNNING);

        proceed.countDown();
        worker.join(5_000);
        JobStatus done = mine.next(status -> status.state() == JobState.COMPLETED);
        assertThat(done.progress().processed()).isEqualTo(3);
        assertThat(mine.events).anySatisfy(event -> assertThat(event.message()).isEqualTo("Phase: Seiten lesen"));
        assertThat(foreign.changes.poll(500, TimeUnit.MILLISECONDS)).as("fremder Auftraggeber").isNull();
        assertThat(foreign.events).isEmpty();
    }

    @Test
    void aDeletionArrivesAndAClosedSubscriptionGetsNothingMore() throws InterruptedException {
        jobs = new Jobs(store, executor, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        Subscription subscription = jobs.subscribe("client-a", mine);
        String id = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { })).id();
        executor.runNext();
        mine.next(status -> status.state() == JobState.COMPLETED);

        jobs.delete(id, "client-a");
        assertThat(mine.removed.poll(5, TimeUnit.SECONDS)).isEqualTo(id);

        subscription.close();
        jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { }));
        executor.runNext();
        assertThat(mine.changes.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers() throws InterruptedException {
        jobs = new Jobs(store, executor, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        jobs.subscribe("client-a", status -> {
            throw new IllegalStateException("kaputt");
        });
        jobs.subscribe("client-a", mine);

        jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { }));
        executor.runNext();

        assertThat(mine.next(status -> status.state() == JobState.COMPLETED)).isNotNull();
    }

    @Test
    void aStepHangingAfterCancelIsDetachedAfterTheGracePeriodWithoutAnyQuery() throws InterruptedException {
        jobs = new Jobs(store, command -> new Thread(command).start(), new JobLimits(1, 5, Duration.ofHours(1)),
                clock);
        jobs.stopWatch(Duration.ofMillis(300));
        jobs.subscribe("client-a", mine);
        CountDownLatch hanging = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String id = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            hanging.countDown();
            await(release);
        })).id();
        assertThat(hanging.await(5, TimeUnit.SECONDS)).isTrue();

        jobs.cancel(id, "client-a");
        clock.advance(Jobs.STOP_GRACE.plusSeconds(1));

        try {
            assertThat(mine.next(status -> status.id().equals(id) && status.state() == JobState.CANCELLED))
                    .isNotNull();
        } finally {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sammelt Meldungen; {@link #next} wartet auf eine passende. */
    private static final class Recorder implements JobListener {
        final BlockingQueue<JobStatus> changes = new LinkedBlockingQueue<>();
        final BlockingQueue<String> removed = new LinkedBlockingQueue<>();
        final List<JobEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void changed(JobStatus status) {
            changes.add(status);
        }

        @Override
        public void removed(JobStatus last) {
            removed.add(last.id());
        }

        @Override
        public void event(JobEvent event) {
            events.add(event);
        }

        JobStatus next(Predicate<JobStatus> wanted) throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                JobStatus status = changes.poll(until - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (status != null && wanted.test(status)) {
                    return status;
                }
            }
            throw new AssertionError("Keine passende Meldung");
        }
    }
}
