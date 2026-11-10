package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Welche Knöpfe ein Auftrag zeigt, entscheidet allein sein Zustandsobjekt. */
class JobLifecycleStateTest {

    private static final Instant T0 = Instant.parse("2026-11-10T15:00:00Z");

    private static JobStatus status(JobType type, JobState state, long failed, boolean incomplete, boolean cancel,
                                    boolean pause) {
        return new JobStatus("j", type, "confluence", "DEV", "client-a", state, new JobProgress(3, 3, 3 - failed,
                failed), state == JobState.FAILED ? new JobError("X", "kaputt") : null, cancel, T0, null, null, null,
                null, Map.of(), incomplete, pause);
    }

    private static List<JobAction> actions(JobType type, JobState state, long failed, boolean incomplete) {
        return status(type, state, failed, incomplete, false, false).lifecycle().actions();
    }

    @Test
    void playAndPauseNeverShowTogetherAndWaitingJobsStartNow() {
        assertThat(actions(JobType.EXPORT, JobState.QUEUED, 0, false))
                .containsExactly(JobAction.START_NOW, JobAction.CANCEL);
        assertThat(actions(JobType.EXPORT, JobState.RUNNING, 0, false))
                .containsExactly(JobAction.PAUSE, JobAction.CANCEL);
        assertThat(actions(JobType.ASSETS, JobState.RUNNING, 0, false))
                .containsExactly(JobAction.PAUSE, JobAction.CANCEL);
        assertThat(actions(JobType.EXPORT, JobState.PAUSED, 0, false))
                .containsExactly(JobAction.RESUME, JobAction.DELETE);
        for (JobState state : JobState.values()) {
            List<JobAction> actions = actions(JobType.SYNC, state, 0, false);
            assertThat(actions.contains(JobAction.PAUSE) && actions.contains(JobAction.RESUME)).isFalse();
        }
    }

    @Test
    void finishedMeansNothingIsMissing() {
        assertThat(status(JobType.EXPORT, JobState.COMPLETED, 0, false, false, false).lifecycle().key())
                .isEqualTo("COMPLETED");
        assertThat(actions(JobType.EXPORT, JobState.COMPLETED, 0, false)).containsExactly(JobAction.DELETE);
        assertThat(status(JobType.EXPORT, JobState.COMPLETED, 1, false, false, false).lifecycle().key())
                .isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(status(JobType.ASSETS, JobState.COMPLETED, 0, true, false, false).lifecycle().key())
                .isEqualTo("COMPLETED_WITH_ERRORS");
    }

    @Test
    void failedAndCancelledDownloadsResumeOtherKindsOnlyDelete() {
        assertThat(actions(JobType.EXPORT, JobState.FAILED, 0, false))
                .containsExactly(JobAction.RESUME, JobAction.DELETE);
        assertThat(actions(JobType.SYNC, JobState.CANCELLED, 0, false))
                .containsExactly(JobAction.RESUME, JobAction.DELETE);
        assertThat(actions(JobType.ENRICHMENT, JobState.FAILED, 0, false)).containsExactly(JobAction.DELETE);
        assertThat(actions(JobType.ENRICHMENT, JobState.RUNNING, 0, false)).containsExactly(JobAction.CANCEL);
    }

    @Test
    void aRequestedPauseCanStillBecomeACancelButNotTheOtherWayRound() {
        JobLifecycleState pausing = status(JobType.EXPORT, JobState.RUNNING, 0, false, true, true).lifecycle();
        assertThat(pausing.key()).isEqualTo("PAUSE_REQUESTED");
        assertThat(pausing.actions()).containsExactly(JobAction.CANCEL);
        JobLifecycleState cancelling = status(JobType.EXPORT, JobState.RUNNING, 0, false, true, false).lifecycle();
        assertThat(cancelling.key()).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelling.actions()).isEmpty();
        assertThat(cancelling.allows(JobAction.PAUSE)).isFalse();
    }
}
