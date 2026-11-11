package com.aresstack.spaceharvester.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStage;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * „Alle anhalten“, „Alle fortsetzen“ und „Alle abbrechen“ (Angelo 11.11.2026): welche Aufträge sie betreffen und in
 * welcher Reihenfolge.
 */
class AllJobsTest {

    @Test
    void pausingAllHoldsWaitingJobsFirstSoNoneMovesUp() {
        JobStatus running = job("run", JobType.EXPORT, JobState.RUNNING, null, 1);
        JobStatus waiting = job("wait", JobType.EXPORT, JobState.QUEUED, null, 2);
        JobStatus sync = job("sync", JobType.SYNC, JobState.RUNNING, null, 3);
        JobStatus prepared = job("prep", JobType.ASSETS, JobState.RUNNING, JobStage.PREPARED, 4);
        JobStatus downloading = job("load", JobType.ATTACHMENTS, JobState.RUNNING, JobStage.DOWNLOADING, 5);
        // Folgt seinem Export und bleibt wie in seiner Zeile unberührt; Anreicherungen lassen sich nicht anhalten.
        JobStatus awaiting = job("await", JobType.ASSETS, JobState.QUEUED, JobStage.AWAITING, 6);
        JobStatus enrichment = job("enrich", JobType.ENRICHMENT, JobState.RUNNING, null, 7);
        JobStatus done = job("done", JobType.EXPORT, JobState.COMPLETED, null, 8);
        JobStatus paused = job("paused", JobType.EXPORT, JobState.PAUSED, null, 9);

        List<JobStatus> jobs = List.of(running, waiting, sync, prepared, downloading, awaiting, enrichment, done, paused);
        assertThat(AllJobs.toPause(jobs)).extracting(JobStatus::id)
                .containsExactly("wait", "prep", "run", "sync", "load");
        assertThat(AllJobs.pausable(stopping("stop", false))).as("Abbruch angefordert").isFalse();
        assertThat(AllJobs.pausable(stopping("halt", true))).as("Anhalten angefordert").isFalse();
    }

    @Test
    void resumingAllContinuesOnlyPausedJobsOldestFirst() {
        JobStatus newer = paused("newer", 5, PauseReason.USER);
        JobStatus older = paused("older", 1, PauseReason.CONNECTION_LOST);
        JobStatus signIn = paused("signin", 3, PauseReason.SIGN_IN_REQUIRED);
        List<JobStatus> jobs = List.of(newer, job("done", JobType.EXPORT, JobState.COMPLETED, null, 2), older,
                failed("failed", 4), job("cancelled", JobType.EXPORT, JobState.CANCELLED, null, 6), signIn,
                job("run", JobType.EXPORT, JobState.RUNNING, null, 7));

        // Fertige, gescheiterte und abgebrochene bleiben, wie sie sind.
        assertThat(AllJobs.toResume(jobs)).extracting(JobStatus::id).containsExactly("older", "signin", "newer");
    }

    @Test
    void cancellingAllStopsWhatTheStateLetsCancelWaitingFirst() {
        List<JobStatus> jobs = List.of(job("run", JobType.EXPORT, JobState.RUNNING, null, 1),
                job("wait", JobType.SYNC, JobState.QUEUED, null, 2),
                job("enrich", JobType.ENRICHMENT, JobState.RUNNING, null, 3),
                stopping("halt", true),
                job("await", JobType.ATTACHMENTS, JobState.QUEUED, JobStage.AWAITING, 5),
                paused("paused", 6, PauseReason.USER),
                job("done", JobType.EXPORT, JobState.COMPLETED, null, 7));

        assertThat(AllJobs.toCancel(jobs)).extracting(JobStatus::id).containsExactly("wait", "run", "enrich", "halt");
        for (JobStatus job : AllJobs.toCancel(jobs)) {
            assertThat(job.lifecycle().allows(JobAction.CANCEL)).isTrue();
        }
    }

    @Test
    void oneFailureDoesNotStopTheOthersAndVanishedJobsAreNoFailure() {
        List<JobStatus> jobs = List.of(job("a", JobType.EXPORT, JobState.RUNNING, null, 1),
                job("gone", JobType.EXPORT, JobState.RUNNING, null, 2),
                job("turned", JobType.EXPORT, JobState.RUNNING, null, 3),
                job("broken", JobType.EXPORT, JobState.RUNNING, null, 4),
                job("b", JobType.EXPORT, JobState.RUNNING, null, 5));
        IllegalStateException problem = new IllegalStateException("kaputt");

        AllJobs.Outcome outcome = AllJobs.apply(jobs, job -> {
            switch (job.id()) {
                case "gone" -> throw new UnknownJobException(job.id());
                case "turned" -> throw new JobActionNotAllowedException(job.id(), JobAction.PAUSE,
                        job.lifecycle());
                case "broken" -> throw problem;
                default -> { }
            }
        });

        assertThat(outcome.changed()).isEqualTo(2);
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.problem()).isSameAs(problem);
    }

    private static JobStatus job(String id, JobType type, JobState state, JobStage stage, int minute) {
        return new JobStatus(id, type, "wiki", "OPS", "local", state, new JobProgress(10, 4, 4, 0),
                state == JobState.FAILED ? new JobError("X", "kaputt") : null, false,
                Instant.parse("2026-11-11T10:00:00Z").plusSeconds(60L * minute), null, null, null, null,
                Map.of(), false, false, null, stage);
    }

    private static JobStatus paused(String id, int minute, PauseReason reason) {
        return new JobStatus(id, JobType.EXPORT, "wiki", "OPS", "local", JobState.PAUSED, new JobProgress(10, 4, 4, 0),
                null, false, Instant.parse("2026-11-11T10:00:00Z").plusSeconds(60L * minute), null, null, null, null,
                Map.of(), false, false, reason);
    }

    private static JobStatus failed(String id, int minute) {
        return job(id, JobType.EXPORT, JobState.FAILED, null, minute);
    }

    /** Laufend mit angefordertem Abbruch oder, mit {@code pausing}, angefordertem Anhalten. */
    private static JobStatus stopping(String id, boolean pausing) {
        return new JobStatus(id, JobType.EXPORT, "wiki", "OPS", "local", JobState.RUNNING, new JobProgress(10, 4, 4, 0),
                null, true, Instant.parse("2026-11-11T10:04:00Z"), null, null, null, null, Map.of(), false, pausing,
                null);
    }
}
