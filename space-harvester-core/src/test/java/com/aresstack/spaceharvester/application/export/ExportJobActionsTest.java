package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobcontrol.JobActions;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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
                JobAction.CANCEL);
        assertThatThrownBy(() -> actions.perform(export.id(), "client-a", JobAction.RESUME))
                .isInstanceOf(JobActionNotAllowedException.class)
                .hasMessageContaining("RUNNING");
        assertThatThrownBy(() -> actions.perform(export.id(), "client-a", JobAction.DELETE))
                .isInstanceOf(JobActionNotAllowedException.class);

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
    void anotherClientsJobStaysUnknown() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null)));
        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of());

        assertThatThrownBy(() -> actions.perform(export.id(), "client-b", JobAction.PAUSE))
                .isInstanceOf(UnknownJobException.class);
    }
}
