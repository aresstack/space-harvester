package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Lokale Arbeit belegt keinen Platz unter „Vorbereitung“ (Angelo 24.11.2026: „die Plätze beziehen sich ja auf die
 * KI-Ressourcen im Netz, nicht auf die lokalen Ressourcen am PC“): Aufträge mit {@link JobType#local()} laufen auf
 * eigener Spur bis {@link Jobs#localSlots()}.
 */
class JobsLocalLaneTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobStore store = new InMemoryJobStore();

    private Jobs jobs(int preparations) {
        Jobs jobs = new Jobs(store, executor, new JobLimits(preparations, 5, Duration.ofHours(1)), clock);
        jobs.localSlots(1);
        return jobs;
    }

    private static JobTask blocking() {
        return JobTask.blocking(JobContext::checkpoint);
    }

    @Test
    void onlyLocalWorkIsTheLocalKind() {
        assertThat(JobType.STATEMENT_ANALYSIS.local()).as("MaltParser").isTrue();
        assertThat(JobType.REREAD.local()).isTrue();
        assertThat(JobType.IMPORT.local()).isTrue();
        assertThat(JobType.PURGE.local()).isTrue();
        assertThat(JobType.KNOWLEDGE_GRAPH_EXPORT.local()).isTrue();
        assertThat(JobType.CONVERSION.local()).isTrue();
        assertThat(JobType.PRESENTATION_EXPORT.local()).isTrue();
        // KI im Netz oder die Quelle: weiter auf den Plätzen.
        for (JobType type : new JobType[] {JobType.SYNC, JobType.EXPORT, JobType.ENRICHMENT, JobType.GLOSSARY,
                JobType.UPDATE_SUMMARY, JobType.STATEMENT_CHECK, JobType.READING_PREFETCH, JobType.ASSETS,
                JobType.BACKGROUND, JobType.VIDEO_SLIDES}) {
            assertThat(type.local()).as(type.name()).isFalse();
        }
    }

    @Test
    void aLocalJobStartsAlthoughAllPlacesAreTakenAndTakesNoPlaceItself() {
        Jobs jobs = jobs(1);
        JobStatus sync = jobs.submit(JobType.SYNC, "wiki", "DEV", "client-a", blocking());
        JobStatus analysis = jobs.submit(JobType.STATEMENT_ANALYSIS, "wiki", "DEV", "client-a", blocking());
        JobStatus export = jobs.submit(JobType.EXPORT, "wiki", "OPS", "client-a", blocking());

        assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(analysis.id()).state()).as("eigene Spur").isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(export.id()).state()).as("der eine Platz ist belegt").isEqualTo(JobState.QUEUED);

        jobs.cancel(sync.id(), "client-a");
        executor.runNext();

        assertThat(jobs.status(export.id()).state()).as("der Platz des Abgleichs, nicht der Analyse")
                .isEqualTo(JobState.RUNNING);
    }

    @Test
    void aWaitingLocalJobHoldsBackNoOtherJobAndStartsWhenALocalPlaceIsFree() {
        Jobs jobs = jobs(2);
        JobStatus first = jobs.submit(JobType.REREAD, "wiki", "DEV", "client-a", blocking());
        JobStatus second = jobs.submit(JobType.CONVERSION, "wiki", "DEV", "client-a", blocking());
        JobStatus summary = jobs.submit(JobType.UPDATE_SUMMARY, "wiki", "DEV", "client-a", blocking());

        assertThat(jobs.status(first.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(second.id()).state()).as("ein lokaler Platz").isEqualTo(JobState.QUEUED);
        assertThat(jobs.status(summary.id()).state()).as("der Folgeauftrag nimmt seinen Platz")
                .isEqualTo(JobState.RUNNING);

        jobs.cancel(first.id(), "client-a");
        executor.runNext();

        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void moreLocalPlacesTakeEffectAtOnce() {
        Jobs jobs = jobs(1);
        JobStatus first = jobs.submit(JobType.IMPORT, "wiki", null, "client-a", blocking());
        JobStatus second = jobs.submit(JobType.KNOWLEDGE_GRAPH_EXPORT, "wiki", null, "client-a", blocking());
        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.QUEUED);

        jobs.localSlots(2);

        assertThat(jobs.status(first.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(jobs.status(second.id()).state()).isEqualTo(JobState.RUNNING);
        assertThat(Jobs.defaultLocalSlots()).isPositive();
    }
}
