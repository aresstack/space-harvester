package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Die Zeilenaktionen kommen vom Zustandsobjekt des Core (ADR 0025): Pause und Play nie zugleich, kein eigener
 * Pfeil im Kreis mehr; die Oberfläche liefert nur Symbol und Tooltip je Aktion und die Texte der Zustände.
 */
class ActivitiesActionsTest {

    @Test
    void theCoreStateDecidesWhichActionsARowOffers() {
        assertThat(job(JobType.EXPORT, JobState.RUNNING, false).lifecycle().actions())
                .containsExactly(JobAction.PAUSE, JobAction.CANCEL);
        assertThat(job(JobType.EXPORT, JobState.PAUSED, false).lifecycle().actions())
                .containsExactly(JobAction.RESUME, JobAction.DELETE);
        assertThat(job(JobType.EXPORT, JobState.FAILED, false).lifecycle().actions())
                .containsExactly(JobAction.RESUME, JobAction.DELETE);
        assertThat(job(JobType.ENRICHMENT, JobState.RUNNING, false).lifecycle().actions())
                .containsExactly(JobAction.CANCEL);
        assertThat(job(JobType.EXPORT, JobState.QUEUED, false).lifecycle().actions())
                .containsExactly(JobAction.START_NOW, JobAction.CANCEL);
        assertThat(job(JobType.EXPORT, JobState.COMPLETED, false).lifecycle().actions())
                .containsExactly(JobAction.DELETE);
        // Kein Zustand bietet mehr als zwei Aktionen an: so breit ist die Spalte.
        for (JobType type : JobType.values()) {
            for (JobState state : JobState.values()) {
                assertThat(job(type, state, false).lifecycle().actions().size())
                        .isLessThanOrEqualTo(ActivitiesView.MAX_LIFECYCLE_ACTIONS);
            }
        }
    }

    @Test
    void everyActionHasAnIconAndATooltipInPlainWords() {
        JobStatus running = job(JobType.EXPORT, JobState.RUNNING, false);
        for (JobAction action : JobAction.values()) {
            assertThat(ActivitiesView.actionIcon(action)).isNotNull();
            assertThat(ActivitiesView.actionTip(action, running)).isNotBlank();
        }
        assertThat(ActivitiesView.actionTip(JobAction.PAUSE, running))
                .isEqualTo("Anhalten · Geladenes bleibt, später fortsetzen");
        assertThat(ActivitiesView.actionTip(JobAction.RESUME, job(JobType.EXPORT, JobState.PAUSED, false)))
                .isEqualTo("Fortsetzen · holt nur, was noch fehlt");
        assertThat(ActivitiesView.actionTip(JobAction.RESUME, job(JobType.EXPORT, JobState.FAILED, false),
                dataset(new ExportDatasetInfo.Media(3, 0, 2, 0))))
                .isEqualTo("Fortsetzen · 2 Dateien fehlen wegen fehlender Anmeldung; "
                        + "die Einmalcode-Abfrage kommt dann wieder");
        assertThat(ActivitiesView.actionTip(JobAction.CANCEL, running)).isEqualTo("Abbrechen");
        assertThat(ActivitiesView.actionTip(JobAction.CANCEL, job(JobType.EXPORT, JobState.RUNNING, true)))
                .isEqualTo("Abbrechen · statt nur anzuhalten");
    }

    @Test
    void theStateTextFollowsTheCoreStateKey() {
        assertThat(Texts.state(job(JobType.EXPORT, JobState.RUNNING, false))).isEqualTo("Läuft");
        assertThat(Texts.state(job(JobType.EXPORT, JobState.RUNNING, true))).isEqualTo("Anhalten angefordert");
        assertThat(Texts.state(job(JobType.EXPORT, JobState.PAUSED, false))).isEqualTo("Angehalten");
        JobStatus complete = job(JobType.EXPORT, JobState.COMPLETED, false);
        assertThat(Texts.state(complete)).isEqualTo("Fertig");
        assertThat(Texts.tone(complete)).isEqualTo(Comic.Tone.GOOD);
        JobStatus incomplete = new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.COMPLETED,
                new JobProgress(5, 5, 3, 2), null, false, Instant.EPOCH, null, Instant.EPOCH, null, null, Map.of(),
                true, false);
        assertThat(Texts.state(incomplete)).isEqualTo("Fertig, unvollständig");
        assertThat(Texts.tone(incomplete)).isEqualTo(Comic.Tone.WARNING);
    }

    private static ExportDatasetInfo dataset(ExportDatasetInfo.Media media) {
        return new ExportDatasetInfo("j1", "wiki", "OPS", 3, true, ExportDatasetInfo.Images.NOT_COLLECTED,
                List.of("json"), media);
    }

    /** @param pausing laufend mit angefordertem Anhalten (cancelRequested + pauseRequested) */
    private static JobStatus job(JobType type, JobState state, boolean pausing) {
        return new JobStatus("j1", type, "wiki", "OPS", "local", state, new JobProgress(0, 0, 0, 0),
                state == JobState.FAILED ? new JobError("X", "kaputt") : null, pausing, Instant.EPOCH, null, null,
                null, null, Map.of(), false, pausing);
    }
}
