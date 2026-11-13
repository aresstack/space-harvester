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
                null, Map.of(), incomplete, pause, null);
    }

    private static List<JobAction> actions(JobType type, JobState state, long failed, boolean incomplete) {
        return status(type, state, failed, incomplete, false, false).lifecycle().actions();
    }

    @Test
    void imagesWaitingForTheTextOfTheirExportOfferOnlyDelete() {
        JobStatus waiting = new JobStatus("j", JobType.ASSETS, "confluence", "DEV", "client-a", JobState.QUEUED,
                new JobProgress(0, 0, 0, 0), null, false, T0, null, null, null, null, Map.of(), false, false, null,
                JobStage.AWAITING);
        assertThat(waiting.lifecycle().key()).isEqualTo("AWAITING_EXPORT");
        assertThat(waiting.lifecycle().actions()).containsExactly(JobAction.DELETE);
        // Nur ein wartender Auftrag wartet auf seinen Vorgänger.
        JobStatus running = new JobStatus("j", JobType.ASSETS, "confluence", "DEV", "client-a", JobState.RUNNING,
                new JobProgress(0, 0, 0, 0), null, false, T0, T0, null, null, null, Map.of(), false, false, null,
                JobStage.AWAITING);
        assertThat(running.stage()).isNull();
        JobStatus done = new JobStatus("j", JobType.ASSETS, "confluence", "DEV", "client-a", JobState.COMPLETED,
                new JobProgress(0, 0, 0, 0), null, false, T0, T0, T0, null, null, Map.of(), false, false, null,
                JobStage.AWAITING);
        assertThat(done.stage()).isNull();
    }

    @Test
    void playAndPauseNeverShowTogetherAndWaitingJobsStartNow() {
        assertThat(actions(JobType.EXPORT, JobState.QUEUED, 0, false))
                .containsExactly(JobAction.START_NOW, JobAction.CANCEL, JobAction.DELETE);
        assertThat(actions(JobType.EXPORT, JobState.RUNNING, 0, false))
                .containsExactly(JobAction.PAUSE, JobAction.CANCEL, JobAction.DELETE);
        assertThat(actions(JobType.ASSETS, JobState.RUNNING, 0, false))
                .containsExactly(JobAction.PAUSE, JobAction.CANCEL, JobAction.DELETE);
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
        assertThat(actions(JobType.EXPORT, JobState.COMPLETED, 0, false))
                .containsExactly(JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(actions(JobType.EXPORT, JobState.COMPLETED, 1, false))
                .containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(actions(JobType.SYNC, JobState.COMPLETED, 0, false))
                .containsExactly(JobAction.FORCE_REFRESH, JobAction.DELETE);
        // Bilder und Anhänge sind Downloads: unvollständig → nur Fehlendes neu holen, immer auch neu erzwingen.
        assertThat(actions(JobType.ASSETS, JobState.COMPLETED, 1, false))
                .containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(actions(JobType.ATTACHMENTS, JobState.COMPLETED, 0, true))
                .containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(actions(JobType.ASSETS, JobState.COMPLETED, 0, false))
                .containsExactly(JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(actions(JobType.ATTACHMENTS, JobState.CANCELLED, 0, false))
                .containsExactly(JobAction.RESUME, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(status(JobType.EXPORT, JobState.COMPLETED, 1, false, false, false).lifecycle().key())
                .isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(status(JobType.ASSETS, JobState.COMPLETED, 0, true, false, false).lifecycle().key())
                .isEqualTo("COMPLETED_WITH_ERRORS");
    }

    @Test
    void failedAndCancelledDownloadsResumeOtherKindsOnlyDelete() {
        assertThat(actions(JobType.EXPORT, JobState.FAILED, 0, false))
                .containsExactly(JobAction.RESUME, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(actions(JobType.SYNC, JobState.CANCELLED, 0, false))
                .containsExactly(JobAction.RESUME, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(actions(JobType.ENRICHMENT, JobState.FAILED, 0, false)).containsExactly(JobAction.DELETE);
        assertThat(actions(JobType.ENRICHMENT, JobState.RUNNING, 0, false))
                .containsExactly(JobAction.CANCEL, JobAction.DELETE);
    }

    @Test
    void aSyncThatConfirmedButMissedPagesIsFinishedWithErrors() {
        JobStatus partial = new JobStatus("j", JobType.SYNC, "confluence", "DEV", "client-a", JobState.FAILED,
                new JobProgress(3, 3, 2, 1), new JobError(JobLifecycleState.SYNC_PARTIAL, "1 Seite fehlt"), false, T0,
                null, null, null, null, Map.of(), false, false, null);
        assertThat(partial.lifecycle().key()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(partial.lifecycle().actions())
                .containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH, JobAction.DELETE);
        // Ein Abgleich ohne bestätigte Generation setzt fort, wie bisher.
        assertThat(actions(JobType.SYNC, JobState.FAILED, 0, false))
                .containsExactly(JobAction.RESUME, JobAction.FORCE_REFRESH, JobAction.DELETE);
    }

    @Test
    void everyStateOffersDeleteAsItsLastActionAndNeverMoreThanFour() {
        for (JobType type : JobType.values()) {
            for (JobState state : JobState.values()) {
                for (boolean cancel : new boolean[] {false, true}) {
                    for (long failed : new long[] {0, 2}) {
                        List<JobAction> actions = status(type, state, failed, false, cancel, false).lifecycle()
                                .actions();
                        assertThat(actions).last().isEqualTo(JobAction.DELETE);
                        assertThat(actions).hasSizeLessThanOrEqualTo(4);
                        assertThat(actions).doesNotHaveDuplicates();
                    }
                }
            }
        }
    }

    @Test
    void retryingFailuresIsOfferedInEveryStateOnceSomethingFailed() {
        for (JobType type : List.of(JobType.EXPORT, JobType.ASSETS, JobType.ATTACHMENTS, JobType.SYNC)) {
            assertThat(status(type, JobState.RUNNING, 2, false, false, false).lifecycle().actions())
                    .containsExactly(JobAction.RETRY_FAILED, JobAction.PAUSE, JobAction.CANCEL, JobAction.DELETE);
            assertThat(status(type, JobState.PAUSED, 2, false, false, false).lifecycle().actions())
                    .containsExactly(JobAction.RETRY_FAILED, JobAction.RESUME, JobAction.DELETE);
            assertThat(status(type, JobState.CANCELLED, 2, false, false, false).lifecycle().actions())
                    .containsExactly(JobAction.RETRY_FAILED, JobAction.RESUME, JobAction.FORCE_REFRESH,
                            JobAction.DELETE);
            // Ohne Gescheitertes bleibt es, wie es war.
            assertThat(status(type, JobState.RUNNING, 0, false, false, false).lifecycle().actions())
                    .containsExactly(JobAction.PAUSE, JobAction.CANCEL, JobAction.DELETE);
            assertThat(status(type, JobState.PAUSED, 0, false, false, false).lifecycle().actions())
                    .containsExactly(JobAction.RESUME, JobAction.DELETE);
        }
        // Wer nichts nachladen kann, bekommt auch nichts angeboten.
        assertThat(status(JobType.ENRICHMENT, JobState.RUNNING, 2, false, false, false).lifecycle()
                .allows(JobAction.RETRY_FAILED)).isFalse();
    }

    @Test
    void aCatalogOnlySyncHasNothingToForce() {
        JobStatus catalog = new JobStatus("j", JobType.SYNC, "confluence", "DEV", null, JobState.COMPLETED,
                new JobProgress(3, 3, 3, 0), null, false, T0, null, null, null, null,
                Map.of(JobLifecycleState.SYNC_MODE, JobLifecycleState.CATALOG_ONLY), false, false, null);
        assertThat(catalog.lifecycle().actions()).containsExactly(JobAction.DELETE);
    }

    @Test
    void anOutputFromTheHoldingsAndItsFilesHaveNothingToForceButRetryTheirFailures() {
        Map<String, String> holdings = Map.of(JobLifecycleState.HOLDINGS, "true");
        for (JobType type : List.of(JobType.EXPORT, JobType.ASSETS, JobType.ATTACHMENTS)) {
            JobStatus done = new JobStatus("j", type, "confluence", "DEV", "client-a", JobState.COMPLETED,
                    new JobProgress(3, 3, 3, 0), null, false, T0, null, null, null, null, holdings, false, false, null);
            assertThat(done.lifecycle().actions()).containsExactly(JobAction.DELETE);
            JobStatus incomplete = new JobStatus("j", type, "confluence", "DEV", "client-a", JobState.COMPLETED,
                    new JobProgress(3, 3, 2, 1), null, false, T0, null, null, null, null, holdings, true, false, null);
            assertThat(incomplete.lifecycle().actions()).containsExactly(JobAction.RETRY_FAILED, JobAction.DELETE);
            JobStatus cancelled = new JobStatus("j", type, "confluence", "DEV", "client-a", JobState.CANCELLED,
                    new JobProgress(3, 2, 2, 0), null, false, T0, null, null, null, null, holdings, false, false, null);
            assertThat(cancelled.lifecycle().actions()).doesNotContain(JobAction.FORCE_REFRESH);
        }
    }

    @Test
    void aRequestedPauseCanStillBecomeACancelButNotTheOtherWayRound() {
        JobLifecycleState pausing = status(JobType.EXPORT, JobState.RUNNING, 0, false, true, true).lifecycle();
        assertThat(pausing.key()).isEqualTo("PAUSE_REQUESTED");
        assertThat(pausing.actions()).containsExactly(JobAction.CANCEL, JobAction.DELETE);
        JobLifecycleState cancelling = status(JobType.EXPORT, JobState.RUNNING, 0, false, true, false).lifecycle();
        assertThat(cancelling.key()).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelling.actions()).containsExactly(JobAction.DELETE);
        assertThat(cancelling.allows(JobAction.PAUSE)).isFalse();
    }

    @Test
    void whileARetryRestartIsPendingTheJobCanStillBePausedOrCancelled() {
        JobStatus restarting = new JobStatus("j", JobType.ASSETS, "confluence", "DEV", "client-a", JobState.RUNNING,
                new JobProgress(3, 3, 1, 2), null, true, T0, T0, null, null, null, Map.of(), false, true,
                PauseReason.RETRY);
        assertThat(restarting.lifecycle().key()).isEqualTo("RESTARTING");
        assertThat(restarting.lifecycle().actions()).containsExactly(JobAction.PAUSE, JobAction.CANCEL,
                JobAction.DELETE);
        // Angehalten zählt es wie ein Anhalten durch den Benutzer.
        assertThat(new JobLifecycleState.Paused(PauseReason.RETRY).key()).isEqualTo("PAUSED");
    }
}
