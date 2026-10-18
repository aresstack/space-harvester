package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
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
        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Konvertieren (pdf)", progress))
                .isEqualTo(new ActivitiesView.Step("Konvertieren (pdf)", 0, 0));
        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Ablegen", progress).total()).isZero();
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
    void collectingImagesCountsCheckedAndStoredImagesAndTheCardSaysExportingStillWorks() {
        assertThat(ActivitiesView.Step.of(JobType.ASSETS, "Bilder beschaffen", new JobProgress(12, 5, 4, 1)))
                .isEqualTo(new ActivitiesView.Step("Bilder beschaffen · 5 von 12 Bildern geprüft, 4 abgelegt", 5, 12));
        assertThat(ActivitiesView.Counters.of(JobType.ASSETS))
                .isEqualTo(new ActivitiesView.Counters("Bildverweise", "Geprüft", "Abgelegt", "Fehlend"));
        assertThat(ActivitiesView.cardImages(ExportDatasetInfo.Images.COLLECTING))
                .startsWith("Bilder: werden beschafft").contains("noch ohne Bilder");
        assertThat(ActivitiesView.cardImages(ExportDatasetInfo.Images.COMPLETE)).isEqualTo("Bilder: vollständig");
        assertThat(ActivitiesView.cardImages(ExportDatasetInfo.Images.INCOMPLETE)).startsWith("Bilder: unvollständig");
    }

    @Test
    void aSyncWithoutPhaseStillShowsItsPages() {
        assertThat(ActivitiesView.Step.of(JobType.SYNC, null, new JobProgress(10, 3, 3, 0)).text())
                .isEqualTo("3 von 10 Seiten geladen oder geprüft");
    }
}
