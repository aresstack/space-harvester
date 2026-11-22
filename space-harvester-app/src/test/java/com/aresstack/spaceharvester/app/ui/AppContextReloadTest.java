package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.images.ImageDescriptionJobs;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import com.aresstack.spaceharvester.application.links.PageLinks;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AppContext#abortedByReload} (Abnahme #1073): Vor dem Übernehmen fragt der Dialog nach Aufträgen des Benutzers,
 * auch nach Hintergrundteilen, die er startete; Automatik und Abgleiche beim Start zählen nicht. Nur erfundene Daten.
 */
class AppContextReloadTest {

    private static final Instant T = Instant.parse("2026-11-22T01:00:00Z");

    @Test
    void backgroundPartsAUserStartedCountAutomaticOnesAndTheStartScansNot() {
        assertThat(AppContext.abortedByReload(job(JobType.SYNC, JobState.RUNNING, Map.of())))
                .as("Abgleich des Benutzers").isTrue();
        // Fundstelle 22: Das Löschen einer entfernten Quelle übersteht das Neuladen und setzt danach fort.
        assertThat(AppContext.abortedByReload(job(JobType.PURGE, JobState.RUNNING, Map.of(
                com.aresstack.spaceharvester.application.jobs.Jobs.SURVIVES_SHUTDOWN, "true")))).isFalse();
        assertThat(AppContext.abortedByReload(job(JobType.SYNC, JobState.COMPLETED, Map.of())))
                .as("fertig").isFalse();

        // Benutzer: „Bilder beschreiben“ aus dem Planer, Aussagenanalyse mit Eigentümer.
        assertThat(AppContext.abortedByReload(background(ImageDescriptionJobs.KIND, JobState.RUNNING, Map.of(
                ImageDescriptionJobs.TRIGGER, ImageDescriptionJobs.USER,
                ImageDescriptionJobs.PRINCIPAL, "erika")))).isTrue();
        assertThat(AppContext.abortedByReload(background(StatementAnalysisJobs.KIND, JobState.QUEUED, Map.of(
                StatementAnalysisJobs.OWNER, "erika", StatementAnalysisJobs.CALLER, "token-1")))).isTrue();
        assertThat(AppContext.abortedByReload(background(StatementAnalysisJobs.KIND, JobState.PAUSED, Map.of(
                StatementAnalysisJobs.OWNER, "erika")))).as("angehalten: nicht abgebrochen").isFalse();

        // Automatik und Abgleich beim Start.
        assertThat(AppContext.abortedByReload(background(ImageDescriptionJobs.KIND, JobState.RUNNING, Map.of(
                ImageDescriptionJobs.TRIGGER, ImageDescriptionJobs.AUTOMATIC)))).isFalse();
        assertThat(AppContext.abortedByReload(background(PageLinks.BACKGROUND_KIND, JobState.RUNNING, Map.of())))
                .isFalse();

        assertThat(BackgroundJobs.TRIGGER).isEqualTo(ImageDescriptionJobs.TRIGGER);
        assertThat(BackgroundJobs.USER).isEqualTo(ImageDescriptionJobs.USER);
    }

    private static JobStatus background(String kind, JobState state, Map<String, String> parameters) {
        Map<String, String> all = new HashMap<>(parameters);
        all.put(BackgroundJobs.KIND, kind);
        all.put(BackgroundJobs.GROUP, "g1");
        return job(JobType.BACKGROUND, state, all);
    }

    private static JobStatus job(JobType type, JobState state, Map<String, String> parameters) {
        return new JobStatus("j-" + type + "-" + state + "-" + parameters.hashCode(), type, "wiki", "ALPHA",
                "space-harvester-core", state, new JobProgress(0, 0, 0, 0), null, false, T,
                state == JobState.QUEUED ? null : T, null, null, null, parameters, false, false, null);
    }
}
