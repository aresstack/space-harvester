package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Löschen beendeter Aufträge und ihrer Diagnoseereignisse durch den Auftraggeber. */
class JobsDeletionTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobStore store = new InMemoryJobStore();
    private final InMemoryJobEventStore events = new InMemoryJobEventStore();
    private final Jobs jobs = new Jobs(store, events, sourceId -> Optional.empty(), executor,
            new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(30)), clock);

    private JobStatus finished(String owner, JobType type) {
        JobStatus job = jobs.submit(type, "wiki", "DEV", owner, JobTask.blocking(context -> {
            clock.advance(Duration.ofMinutes(1));
            context.phase("Exports", "Einlesen");
        }));
        executor.runNext();
        return job;
    }

    @Test
    void aFinishedJobIsDeletedWithItsEvents() {
        JobStatus job = finished("client-a", JobType.EXPORT);

        jobs.delete(job.id(), "client-a");

        assertThatThrownBy(() -> jobs.status(job.id())).isInstanceOf(UnknownJobException.class);
        assertThat(events.events(job.id())).isEmpty();
    }

    @Test
    void onlyTheOwnerDeletes() {
        JobStatus job = finished("client-a", JobType.EXPORT);

        assertThatThrownBy(() -> jobs.delete(job.id(), "client-b")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> jobs.deleteEvent(job.id(), 1, "client-b")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> jobs.deleteEventsBefore(job.id(), Instant.MAX, "client-b"))
                .isInstanceOf(UnknownJobException.class);
        assertThat(jobs.deleteFinished("client-b", null)).isZero();
        assertThat(jobs.deleteEventsBefore(Instant.MAX, "client-b")).isZero();

        assertThat(jobs.status(job.id()).id()).isEqualTo(job.id());
        assertThat(jobs.events(job.id())).hasSize(4);
    }

    @Test
    void waitingAndRunningJobsCannotBeDeleted() {
        JobStatus running = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a",
                context -> new CompletableFuture<>());
        executor.runNext();
        JobStatus waiting = jobs.submit(JobType.EXPORT, "wiki", "OPS", "client-a", JobTask.blocking(context -> {
        }));

        for (JobStatus job : new JobStatus[] {running, waiting}) {
            assertThatThrownBy(() -> jobs.delete(job.id(), "client-a")).isInstanceOf(JobStillRunningException.class);
            assertThatThrownBy(() -> jobs.deleteEvent(job.id(), 1, "client-a"))
                    .isInstanceOf(JobStillRunningException.class);
            assertThatThrownBy(() -> jobs.deleteEventsBefore(job.id(), Instant.MAX, "client-a"))
                    .isInstanceOf(JobStillRunningException.class);
        }
        assertThat(jobs.deleteFinished("client-a", null)).isZero();
        assertThat(jobs.deleteEventsBefore(Instant.MAX, "client-a")).isZero();
        assertThat(jobs.events(running.id())).isNotEmpty();
    }

    @Test
    void deletingAllFinishedJobsKeepsOtherOwnersAndOtherTypes() {
        JobStatus export = finished("client-a", JobType.EXPORT);
        JobStatus sync = finished("client-a", JobType.SYNC);
        JobStatus foreign = finished("client-b", JobType.EXPORT);

        assertThat(jobs.deleteFinished("client-a", JobType.SYNC)).isEqualTo(1);
        assertThat(jobs.status(export.id()).id()).isEqualTo(export.id());
        assertThatThrownBy(() -> jobs.status(sync.id())).isInstanceOf(UnknownJobException.class);

        assertThat(jobs.deleteFinished("client-a", null)).isEqualTo(1);
        assertThatThrownBy(() -> jobs.status(export.id())).isInstanceOf(UnknownJobException.class);
        assertThat(jobs.status(foreign.id()).id()).isEqualTo(foreign.id());
        assertThat(events.events(foreign.id())).hasSize(4);
    }

    @Test
    void aSingleEventIsDeletedAndItsNumberIsNeverReissued() {
        JobStatus job = finished("client-a", JobType.EXPORT);

        assertThat(jobs.deleteEvent(job.id(), 4, "client-a")).isTrue();
        assertThat(jobs.deleteEvent(job.id(), 4, "client-a")).isFalse();
        assertThat(jobs.deleteEvent(job.id(), 2, "client-a")).isTrue();

        assertThat(jobs.events(job.id())).extracting(JobEvent::sequence).containsExactly(1L, 3L);
        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void eventsBeforeAPointInTimeAreDeletedButTheJobStays() {
        JobStatus job = finished("client-a", JobType.EXPORT);
        Instant cut = jobs.events(job.id()).get(2).at();

        assertThat(jobs.deleteEventsBefore(job.id(), cut, "client-a")).isEqualTo(2);
        assertThat(jobs.events(job.id())).extracting(JobEvent::sequence).containsExactly(3L, 4L);

        JobStatus other = finished("client-a", JobType.SYNC);
        assertThat(jobs.deleteEventsBefore(Instant.MAX, "client-a")).isEqualTo(6);
        assertThat(jobs.events(job.id())).isEmpty();
        assertThat(jobs.events(other.id())).isEmpty();
        assertThat(jobs.status(other.id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void anInterruptedJobContinuesAfterTheHighestNumber() {
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a",
                context -> new CompletableFuture<>());
        executor.runNext();
        events.delete(job.id(), List.of(1L));

        new Jobs(store, events, sourceId -> Optional.empty(), executor,
                new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(30)), clock);

        assertThat(events.events(job.id())).extracting(JobEvent::sequence).containsExactly(2L, 3L);
    }
}
