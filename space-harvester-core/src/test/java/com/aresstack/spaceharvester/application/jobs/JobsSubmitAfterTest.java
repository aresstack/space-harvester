package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Ein Auftrag, der nach dem erfolgreichen Ende seines Vorgängers von selbst startet ({@link Jobs#submitAfter}). */
class JobsSubmitAfterTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final InMemoryJobStore store = new InMemoryJobStore();
    private final Jobs jobs = new Jobs(store, executor, new JobLimits(2, 5, Duration.ofHours(1)), new MutableClock());

    @AfterEach
    void close() {
        jobs.close();
    }

    @Test
    void theFollowerStartsByItselfWhenItsPredecessorCompletes() {
        String first = submit(JobTask.blocking(context -> { }));
        JobStatus follower = after(first);

        assertThat(follower.stage()).isEqualTo(JobStage.AWAITING);
        assertThat(executor.pending()).as("nur der Vorgänger läuft").isEqualTo(1);
        executor.runNext();
        assertThat(jobs.status(first, "client-a").state()).isEqualTo(JobState.COMPLETED);
        assertThat(executor.pending()).as("der Nachfolger ist frei").isEqualTo(1);
        executor.runNext();

        assertThat(jobs.status(follower.id(), "client-a").state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void aFollowerOfACompletedJobIsQueuedAtOnce() {
        String first = submit(JobTask.blocking(context -> { }));
        executor.runNext();

        JobStatus follower = after(first);

        assertThat(follower.stage()).isNotEqualTo(JobStage.AWAITING);
        executor.runNext();
        assertThat(jobs.status(follower.id(), "client-a").state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void aFollowerOfAFailedJobKeepsWaitingAndVanishesWithIt() {
        String first = submit(JobTask.blocking(context -> {
            throw new IllegalStateException("kaputt");
        }));
        JobStatus follower = after(first);
        executor.runNext();

        assertThat(jobs.status(first, "client-a").state()).isEqualTo(JobState.FAILED);
        assertThat(jobs.status(follower.id(), "client-a").stage()).isEqualTo(JobStage.AWAITING);
        assertThat(executor.pending()).isZero();

        jobs.delete(first, "client-a");
        assertThatThrownBy(() -> jobs.status(follower.id(), "client-a")).isInstanceOf(UnknownJobException.class);
    }

    @Test
    void anUnknownPredecessorIsRejected() {
        assertThatThrownBy(() -> after("gibt-es-nicht")).isInstanceOf(UnknownJobException.class);
    }

    private String submit(JobTask task) {
        return jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", task).id();
    }

    private JobStatus after(String awaited) {
        return jobs.submitAfter(JobType.CONVERSION, "wiki", "DEV", null, "client-a", Map.of(),
                JobTask.blocking(context -> { }), awaited);
    }
}
