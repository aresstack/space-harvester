package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobcontrol.JobActions;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Ein Einstieg für alle Aktionen auf Aufträgen: erlaubt ist nur, was der Zustand anbietet. */
class ExportJobActionsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T15:00:00Z"), ZoneOffset.UTC);
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queue::add, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final Exports exports = new Exports(jobs, cache, new RecordingExportArtifactWriter(), clock);
    private final JobActions actions = new JobActions(jobs, exports, null, null);

    @Test
    void pauseAndResumeGoThroughTheStateAndStayInTheSameEntry() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null)));
        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of());
        assertThat(actions.state(export.id(), "client-a").actions()).containsExactly(JobAction.PAUSE,
                JobAction.CANCEL, JobAction.DELETE);
        assertThatThrownBy(() -> actions.perform(export.id(), "client-a", JobAction.RESUME))
                .isInstanceOf(JobActionNotAllowedException.class)
                .hasMessageContaining("RUNNING");
        assertThat(actions.perform(export.id(), "client-a", JobAction.PAUSE).pauseRequested()).isTrue();
        queue.poll().run();
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.PAUSED);

        assertThat(actions.perform(export.id(), "client-a", JobAction.RESUME).id()).isEqualTo(export.id());
        queue.poll().run();
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(actions.state(export.id(), "client-a").actions()).containsExactly(JobAction.FORCE_REFRESH,
                JobAction.DELETE);

        actions.perform(export.id(), "client-a", JobAction.DELETE);
        assertThatThrownBy(() -> jobs.status(export.id())).isInstanceOf(UnknownJobException.class);
    }

    @Test
    void whileARetryRestartIsPendingPauseIsOfferedAndKeepsTheJobPausedWithoutRestart() {
        List<JobAction> recovered = new ArrayList<>();
        jobs.recovery((status, action) -> recovered.add(action), (delay, task) -> task.run());
        CompletableFuture<Void> done = new CompletableFuture<>();
        List<JobContext> contexts = new ArrayList<>();
        JobTask pass = context -> {
            contexts.add(context);
            return done;
        };
        JobStatus job = jobs.submit(JobType.ASSETS, "confluence", "DEV", "client-a", pass);
        queue.poll().run();
        contexts.get(0).failed(2);

        JobStatus restarting = actions.perform(job.id(), "client-a", JobAction.RETRY_FAILED);
        assertThat(restarting.lifecycle().key()).isEqualTo("RESTARTING");
        assertThat(actions.state(job.id(), "client-a").actions()).containsExactly(JobAction.PAUSE, JobAction.CANCEL,
                JobAction.DELETE);
        // Ein zweites ↻, bevor die Anzeige nachzieht: kein Fehler und kein zweiter Neustart.
        assertThat(actions.perform(job.id(), "client-a", JobAction.RETRY_FAILED).lifecycle().key())
                .isEqualTo("RESTARTING");

        assertThat(actions.perform(job.id(), "client-a", JobAction.PAUSE).lifecycle().key())
                .isEqualTo("PAUSE_REQUESTED");
        try {
            contexts.get(0).checkpoint();
        } catch (JobCancelledException e) {
            done.completeExceptionally(e);
        }

        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.PAUSED);
        assertThat(recovered).isEmpty();
    }

    @Test
    void anotherClientsJobStaysUnknown() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null)));
        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of());

        assertThatThrownBy(() -> actions.perform(export.id(), "client-b", JobAction.PAUSE))
                .isInstanceOf(UnknownJobException.class);
    }
}
