package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hintergrundaufträge ({@link JobType#BACKGROUND}) sind nachrangig (Angelo 21.11.2026). */
class JobsBackgroundTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobStore store = new InMemoryJobStore();

    private Jobs jobs(int slots, int maxQueued) {
        return new Jobs(store, executor, new JobLimits(slots, maxQueued, Duration.ofHours(1)), clock);
    }

    private static JobTask idle() {
        return JobTask.blocking(context -> { });
    }

    @Test
    void aBackgroundJobNeverTakesTheLastSlot() {
        Jobs jobs = jobs(2, 5);

        JobStatus first = jobs.submit(JobType.BACKGROUND, "wiki", "ALPHA", null, null, Map.of(), idle());
        JobStatus second = jobs.submit(JobType.BACKGROUND, "wiki", "BETA", null, null, Map.of(), idle());

        assertThat(jobs.status(first.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.QUEUED);

        // Ein Auftrag des Benutzers startet sofort auf dem freien Platz.
        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "GAMMA", null, null, Map.of(), idle());
        assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void aFollowUpArrivingAfterABackgroundJobLeavesTheUsersSlotFree() {
        Jobs jobs = jobs(2, 5);
        JobStatus background = jobs.submit(JobType.BACKGROUND, "wiki", "ALPHA", null, null, Map.of(), idle());
        JobStatus enrichment = jobs.submit(JobType.ENRICHMENT, "wiki", "BETA", null, null, Map.of(), idle());

        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(enrichment.id()).state()).isEqualTo(JobState.QUEUED);
        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "GAMMA", null, null, Map.of(), idle());
        assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void aBackgroundJobWaitsWhileAnotherJobWaits() {
        Jobs jobs = jobs(2, 5);
        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "ALPHA", null, null, Map.of(), idle());
        JobStatus export = jobs.submit(JobType.EXPORT, "wiki", "BETA", null, null, Map.of(), idle());
        JobStatus later = jobs.submit(JobType.SYNC, "wiki", "DELTA", null, null, Map.of(), idle());
        JobStatus background = jobs.submit(JobType.BACKGROUND, "wiki", "GAMMA", null, null, Map.of(), idle());

        assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(later.id()).state()).isEqualTo(JobState.QUEUED);
        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.QUEUED);

        executor.runNext();

        // Der Abgleich kam vor dem Hintergrund und bekommt den freien Platz.
        assertThat(jobs.status(later.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.QUEUED);

        executor.runNext();
        executor.runNext();

        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.RUNNING);
        executor.runNext();
        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void backgroundJobsHaveTheirOwnPoolAndDoNotFillTheUsersQueue() {
        Jobs jobs = jobs(1, 1);
        for (int i = 0; i < 20; i++) {
            jobs.submit(JobType.BACKGROUND, "wiki", "S" + i, null, "client-a", Map.of(), idle());
        }

        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "ALPHA", null, "client-a", Map.of(), idle());

        assertThat(sync.state()).isIn(JobState.QUEUED, JobState.RUNNING);
    }

    @Test
    void anInterruptedBackgroundJobCanRunAgainInTheSameEntry() {
        // Der erste Dienst endet, ohne dass seine Arbeit lief.
        Jobs jobs = new Jobs(store, new ManualExecutor(), new JobLimits(2, 5, Duration.ofHours(1)), clock);
        JobStatus background = jobs.submit(JobType.BACKGROUND, "wiki", "ALPHA", null, null, Map.of("k", "v"),
                idle());
        Jobs restarted = jobs(2, 5);
        JobStatus interrupted = restarted.status(background.id());
        assertThat(interrupted.state()).isEqualTo(JobState.FAILED);
        assertThat(interrupted.error().code()).isEqualTo(JobLifecycleState.INTERRUPTED);

        restarted.rerun(background.id(), null, idle());
        executor.runNext();

        assertThat(restarted.status(background.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(restarted.status(background.id()).parameters()).containsEntry("k", "v");
    }

    @Test
    void withASingleSlotNoBackgroundJobRunsAndItSaysWhy() {
        Jobs jobs = jobs(1, 5);

        JobStatus background = jobs.submit(JobType.BACKGROUND, "wiki", "ALPHA", null, null, Map.of(), idle());
        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.QUEUED);
        assertThat(jobs.events(background.id())).filteredOn(event -> Jobs.SINGLE_SLOT.equals(event.code()))
                .singleElement().satisfies(event -> {
                    assertThat(event.severity()).isEqualTo(JobEvent.Severity.WARNING);
                    assertThat(event.message()).startsWith("Wartet, nur ein Platz eingestellt");
                });

        // Der eine Platz gehört dem Benutzer.
        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "BETA", null, null, Map.of(), idle());
        assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.RUNNING);
        executor.runNext();
        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.QUEUED);

        // Ab zwei Plätzen läuft er.
        jobs.parallelPreparations(2);
        assertThat(jobs.status(background.id()).state()).isEqualTo(JobState.RUNNING);
    }
}
