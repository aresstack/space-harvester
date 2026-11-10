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
 * Pfeil im Kreis und „Neu erzwingen“ in der Zeile eines Auftrags (ADR 0025): Bei Downloads lädt der Pfeil nur
 * gescheiterte Seiten nach und ist sonst gesperrt; „Neu erzwingen“ gibt es für beendete Downloads. Abgleiche starten
 * mit dem Pfeil wie bisher erneut.
 */
class ActivitiesRetryTest {

    @Test
    void theCircularArrowRetriesOnlyFailedPagesOfADownload() {
        assertThat(ActivitiesView.retryBlocked(failures())).isNull();
        assertThat(ActivitiesView.retryTip(failures(), null)).isEqualTo(ActivitiesView.RETRY_FAILED_TIP)
                .startsWith("Nur Fehler neu versuchen");
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.COMPLETED)))
                .isEqualTo("nichts gescheitert");
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.FAILED)))
                .isEqualTo("nicht fertig geworden, ▶ setzt fort");
        assertThat(ActivitiesView.retryTip(job(JobType.EXPORT, JobState.COMPLETED), null))
                .isEqualTo("Nur Fehler neu versuchen · nichts gescheitert");
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.RUNNING))).isEqualTo("läuft noch");
        assertThat(ActivitiesView.retryBlocked(job(JobType.ENRICHMENT, JobState.COMPLETED)))
                .isEqualTo("nur für Downloads und Abgleiche");
    }

    @Test
    void syncsStillStartAgain() {
        assertThat(ActivitiesView.retryBlocked(job(JobType.SYNC, JobState.COMPLETED))).isNull();
        assertThat(ActivitiesView.retryTip(job(JobType.SYNC, JobState.COMPLETED), null))
                .isEqualTo("Erneut versuchen · denselben Abgleich noch einmal starten");
        assertThat(ActivitiesView.retryTip(job(JobType.SYNC, JobState.RUNNING), null))
                .isEqualTo("Erneut versuchen · läuft noch");
    }

    @Test
    void finishedDownloadsCanBeForcedAnew() {
        assertThat(ActivitiesView.forceBlocked(job(JobType.EXPORT, JobState.COMPLETED))).isNull();
        assertThat(ActivitiesView.forceBlocked(failures())).isNull();
        assertThat(ActivitiesView.forceBlocked(job(JobType.EXPORT, JobState.CANCELLED))).isNull();
        assertThat(ActivitiesView.forceTip(job(JobType.EXPORT, JobState.COMPLETED))).startsWith("Neu erzwingen · ");
        assertThat(ActivitiesView.forceBlocked(job(JobType.EXPORT, JobState.RUNNING))).isEqualTo("läuft noch");
        assertThat(ActivitiesView.forceBlocked(job(JobType.SYNC, JobState.COMPLETED))).isEqualTo("nur für Downloads");
    }

    private static JobStatus failures() {
        return new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.COMPLETED,
                new JobProgress(6, 6, 0, 6), null, false, Instant.EPOCH, null, null, null, null);
    }

    private static JobStatus job(JobType type, JobState state) {
        return new JobStatus("j1", type, "wiki", "OPS", "local", state, new JobProgress(0, 0, 0, 0),
                state == JobState.FAILED ? new JobError("X", "kaputt") : null, false, Instant.EPOCH, null, null,
                null, null);
    }
}
