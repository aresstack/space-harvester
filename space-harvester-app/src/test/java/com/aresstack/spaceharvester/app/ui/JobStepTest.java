package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobStepTest {

    @Test
    void theExportCountsFetchedPagesWhileFetchingAndExportedPagesWhileExporting() {
        JobProgress progress = new JobProgress(127, 127, 40, 0);

        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Einlesen", new JobProgress(127, 90, 0, 0)))
                .isEqualTo(new ActivitiesView.Step("Einlesen · 90 von 127 Seiten geladen oder geprüft", 90, 127));
        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Seiten exportieren", progress))
                .isEqualTo(new ActivitiesView.Step("Seiten exportieren · 40 von 127 exportiert", 40, 127));
        assertThat(ActivitiesView.Counters.of(JobType.EXPORT).written()).isEqualTo("Exportiert");
    }

    @Test
    void theEnrichmentCountsStepsAndNamesItsProcessor() {
        ActivitiesView.Step step = ActivitiesView.Step.of(JobType.ENRICHMENT, "Anreichern: nlp (1/2)",
                new JobProgress(200, 30, 28, 2));

        assertThat(step.text()).isEqualTo("Anreichern: nlp (1/2) · 30 von 200 Schritten");
        assertThat(step.done()).isEqualTo(30);
        assertThat(ActivitiesView.Counters.of(JobType.ENRICHMENT).discovered())
                .isEqualTo("Schritte (Seiten × Prozessoren)");
    }

    @Test
    void aSyncWithoutPhaseStillShowsItsPages() {
        assertThat(ActivitiesView.Step.of(JobType.SYNC, null, new JobProgress(10, 3, 3, 0)).text())
                .isEqualTo("3 von 10 Seiten geladen oder geprüft");
    }
}
