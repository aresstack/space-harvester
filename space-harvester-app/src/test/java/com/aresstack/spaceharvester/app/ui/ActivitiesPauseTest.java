package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pause bzw. Play in der Zeile eines Auftrags (ADR 0025): Pause, solange ein Download oder Abgleich läuft; Play für
 * einen angehaltenen, gescheiterten oder abgebrochenen; gesperrt nach „Fertig“, bei angefordertem Abbruch und für
 * andere Auftragsarten.
 */
class ActivitiesPauseTest {

    @Test
    void runningDownloadsAndSyncsCanBePausedAndStoppedOnesCanBeResumed() {
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.EXPORT, JobState.RUNNING, false))).isNull();
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.SYNC, JobState.QUEUED, false))).isNull();
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.EXPORT, JobState.PAUSED, false))).isNull();
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.EXPORT, JobState.FAILED, false))).isNull();
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.SYNC, JobState.CANCELLED, false))).isNull();
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.EXPORT, JobState.COMPLETED, false)))
                .isEqualTo("schon fertig");
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.EXPORT, JobState.RUNNING, true)))
                .isEqualTo("Abbruch angefordert");
        assertThat(ActivitiesView.pauseResumeBlocked(job(JobType.ENRICHMENT, JobState.RUNNING, false)))
                .isEqualTo("nur für Downloads und Abgleiche");
    }

    @Test
    void theTooltipSaysWhatPauseAndPlayDo() {
        assertThat(ActivitiesView.pauseResumeTip(job(JobType.EXPORT, JobState.RUNNING, false)))
                .isEqualTo("Anhalten · Geladenes bleibt, später fortsetzen");
        assertThat(ActivitiesView.pauseResumeTip(job(JobType.EXPORT, JobState.PAUSED, false)))
                .isEqualTo("Fortsetzen · holt nur, was noch fehlt");
        assertThat(ActivitiesView.pauseResumeTip(job(JobType.EXPORT, JobState.COMPLETED, false)))
                .isEqualTo("Fortsetzen · schon fertig");
        assertThat(ActivitiesView.pauseResumeTip(job(JobType.ENRICHMENT, JobState.RUNNING, false)))
                .isEqualTo("Anhalten · nur für Downloads und Abgleiche");
    }

    private static JobStatus job(JobType type, JobState state, boolean cancelRequested) {
        return new JobStatus("j1", type, "wiki", "OPS", "local", state, new JobProgress(0, 0, 0, 0),
                state == JobState.FAILED ? new JobError("X", "kaputt") : null, cancelRequested, Instant.EPOCH, null,
                null, null, null);
    }
}
