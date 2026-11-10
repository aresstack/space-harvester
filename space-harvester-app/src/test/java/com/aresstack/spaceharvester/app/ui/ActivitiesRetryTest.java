package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Der Pfeil im Kreis in der Zeile eines Auftrags: anklickbar für beendete Downloads und Abgleiche, gesperrt solange
 * der Auftrag läuft und für andere Auftragsarten; nennt im Tooltip, wie viele Dateien wegen fehlender Anmeldung
 * (Einmalcode) fehlen.
 */
class ActivitiesRetryTest {

    @Test
    void finishedDownloadsAndSyncsCanBeRetried() {
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.COMPLETED))).isNull();
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.FAILED))).isNull();
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.CANCELLED))).isNull();
        assertThat(ActivitiesView.retryBlocked(job(JobType.SYNC, JobState.COMPLETED))).isNull();
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.RUNNING))).isEqualTo("läuft noch");
        assertThat(ActivitiesView.retryBlocked(job(JobType.EXPORT, JobState.QUEUED))).isEqualTo("läuft noch");
        assertThat(ActivitiesView.retryBlocked(job(JobType.ENRICHMENT, JobState.COMPLETED)))
                .isEqualTo("nur für Downloads und Abgleiche");
    }

    @Test
    void theTooltipNamesTheFilesMissingForWantOfASignIn() {
        JobStatus done = job(JobType.EXPORT, JobState.COMPLETED);
        assertThat(ActivitiesView.retryTip(done, null))
                .isEqualTo("Erneut versuchen · denselben Download noch einmal starten");
        assertThat(ActivitiesView.retryTip(job(JobType.SYNC, JobState.COMPLETED), null))
                .isEqualTo("Erneut versuchen · denselben Abgleich noch einmal starten");
        assertThat(ActivitiesView.retryTip(done, dataset(new ExportDatasetInfo.Media(3, 0, 2, 0))))
                .isEqualTo("Erneut versuchen · 2 Dateien fehlen wegen fehlender Anmeldung; "
                        + "die Einmalcode-Abfrage kommt dann wieder");
        assertThat(ActivitiesView.retryTip(done, dataset(new ExportDatasetInfo.Media(3, 0, 1, 0))))
                .startsWith("Erneut versuchen · 1 Datei fehlt wegen fehlender Anmeldung");
        assertThat(ActivitiesView.retryTip(job(JobType.EXPORT, JobState.RUNNING), null))
                .isEqualTo("Erneut versuchen · läuft noch");
    }

    @Test
    void aDownloadWithFailuresOffersToRetryOnlyTheFailedPages() {
        JobStatus failures = new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.COMPLETED,
                new JobProgress(6, 6, 0, 6), null, false, Instant.EPOCH, null, null, null, null);

        assertThat(ActivitiesView.retryBlocked(failures)).isNull();
        assertThat(ActivitiesView.retryTip(failures, null)).isEqualTo(ActivitiesView.RETRY_FAILED_TIP)
                .startsWith("Nur Fehler neu versuchen");
    }

    private static ExportDatasetInfo dataset(ExportDatasetInfo.Media media) {
        return new ExportDatasetInfo("j1", "wiki", "OPS", 3, true, ExportDatasetInfo.Images.NOT_COLLECTED, List.of("json"),
                media);
    }

    private static JobStatus job(JobType type, JobState state) {
        return new JobStatus("j1", type, "wiki", "OPS", "local", state, new JobProgress(0, 0, 0, 0),
                state == JobState.FAILED ? new JobError("X", "kaputt") : null, false, Instant.EPOCH, null, null,
                null, null);
    }
}
