package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Vorrang für die offene Ansicht (Angelo 24.11.2026: „das Fenster, das ich aufhabe aktuell, auf das ich warte, sollte
 * ja immer Priorität haben … runter priorisiert wird, wenn ich von der Seite weggehe“).
 */
class JobsPriorityTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobStore store = new InMemoryJobStore();

    @AfterEach
    void forget() {
        Foreground.reset();
    }

    private Jobs jobs(int preparations) {
        return new Jobs(store, executor, new JobLimits(preparations, 5, Duration.ofHours(1)), clock);
    }

    private static JobTask blocking() {
        return JobTask.blocking(JobContext::checkpoint);
    }

    private static JobStatus inView(String view, java.util.function.Supplier<JobStatus> submit) {
        AtomicReference<JobStatus> status = new AtomicReference<>();
        Foreground.run(view, () -> status.set(submit.get()));
        return status.get();
    }

    @Test
    void aJobOfTheOpenViewStartsAtOnceWithoutAPlaceAndLosesThatWhenTheViewIsLeft() {
        Jobs jobs = jobs(1);
        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "DEV", "client-a", blocking());
        Foreground.open("ansicht-1");
        JobStatus hits = inView("ansicht-1", () -> jobs.submitInteractive(JobType.ENRICHMENT, "*", null, null,
                "client-a", java.util.Map.of(), blocking()));
        JobStatus export = jobs.submit(JobType.EXPORT, "wiki", "OPS", "client-a", blocking());

        assertThat(jobs.status(hits.id()).parameter(Jobs.PRIORITY)).isEqualTo("ansicht-1");
        assertThat(jobs.status(hits.id()).state()).as("Vorrang ohne Platz").isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.QUEUED);

        // Die Ansicht wird verlassen: Der laufende Auftrag belegt ab jetzt den Platz, den der Abgleich freigibt.
        Foreground.close("ansicht-1");
        jobs.cancel(sync.id(), "client-a");
        executor.runNext();
        assertThat(jobs.status(export.id()).state()).as("der Suchtreffer-Auftrag belegt jetzt den einen Platz")
                .isEqualTo(JobState.QUEUED);
        assertThat(jobs.events(hits.id())).extracting(JobEvent::code).contains(Jobs.PRIORITY_STARTED,
                Jobs.PRIORITY_ENDED);

        jobs.cancel(hits.id(), "client-a");
        executor.runNext();
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void aWaitingJobOfALeftViewQueuesNormallyAndOnlyOpenViewsMarkJobs() {
        Jobs jobs = jobs(1);
        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "DEV", "client-a", blocking());
        JobStatus export = jobs.submit(JobType.EXPORT, "wiki", "OPS", "client-a", blocking());
        // Nie geöffnet bzw. schon verlassen: kein Vorrang.
        JobStatus closed = inView("ansicht-2", () -> jobs.submit(JobType.EXPORT, "wiki", "QA", "client-a",
                blocking()));
        assertThat(jobs.status(closed.id()).parameter(Jobs.PRIORITY)).isNull();
        assertThat(jobs.status(closed.id()).state()).isEqualTo(JobState.QUEUED);

        jobs.cancel(sync.id(), "client-a");
        executor.runNext();
        assertThat(jobs.status(export.id()).state()).as("in der Reihenfolge").isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(closed.id()).state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void downloadsNeverTakePriority() {
        Jobs jobs = jobs(1);
        Foreground.open("ansicht-3");
        JobStatus images = inView("ansicht-3", () -> jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a",
                blocking()));
        assertThat(jobs.status(images.id()).parameter(Jobs.PRIORITY)).isNull();
    }

    @Test
    void aiCallsOfOrdinaryJobsWaitWhileTheOpenViewWorksAndTheViewItselfNever() throws Exception {
        Foreground.open("ansicht-4");
        Foreground.Hold turn = Foreground.busy("ansicht-4");
        assertThat(Foreground.working()).isTrue();

        // Die Ansicht selbst wartet nie, auch nicht aus einem Auftrag mit ihrem Vorrang.
        Foreground.inJob("ansicht-4", () -> Foreground.aiCall().close());
        Foreground.run("ansicht-4", () -> Foreground.aiCall().close());
        // Ohne Auftrag und ohne Ansicht (etwa ein Klick in der Oberfläche) ebenfalls nicht.
        Foreground.aiCall().close();

        CountDownLatch passed = new CountDownLatch(1);
        Thread job = Thread.ofVirtual().start(() -> Foreground.inJob(null, () -> {
            try (Foreground.Hold call = Foreground.aiCall()) {
                passed.countDown();
            }
        }));
        awaitWaiting(job);
        assertThat(passed.getCount()).as("der gewöhnliche Auftrag lässt den Vortritt").isEqualTo(1);

        turn.close();
        assertThat(passed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(Foreground.working()).isFalse();
    }

    @Test
    void leavingTheViewReleasesTheHeldBackCalls() throws Exception {
        Foreground.open("ansicht-5");
        Foreground.busy("ansicht-5");
        CountDownLatch passed = new CountDownLatch(1);
        Thread job = Thread.ofVirtual().start(() -> Foreground.inJob(null, () -> {
            try (Foreground.Hold call = Foreground.aiCall()) {
                passed.countDown();
            }
        }));
        awaitWaiting(job);

        Foreground.close("ansicht-5");

        assertThat(passed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(Foreground.isOpen("ansicht-5")).isFalse();
    }

    /** Bis der Thread an der Bedingung wartet (ohne feste Pause). */
    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING) {
            assertThat(System.nanoTime()).as("Thread wartet nicht").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
