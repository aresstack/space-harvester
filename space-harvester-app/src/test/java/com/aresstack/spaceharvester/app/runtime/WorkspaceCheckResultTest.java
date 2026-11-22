package com.aresstack.spaceharvester.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.check.CheckUnavailableException;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** „Prüfen“ im Reiter (AT zu #1059): das Ende des Klick-Auftrags wird zum Ergebnis wie früher beim direkten Prüfen. */
class WorkspaceCheckResultTest {

    private static final PageStatements READ = PageStatements.missing(PageStatements.NOT_ANALYSED);

    @Test
    void aCheckedPageIsReadAgain() {
        assertThat(Workspace.checkResult(done(JobState.COMPLETED, new JobProgress(1, 1, 1, 0), null), List.of(),
                () -> READ)).isSameAs(READ);
    }

    @Test
    void withoutAModelWithoutReleaseOrWithoutCurrentAnalysisTheReasonIsKept() {
        assertThatThrownBy(() -> Workspace.checkResult(done(JobState.FAILED, JobProgress.NONE,
                new JobError(StatementAnalysisJobs.CHECK_UNAVAILABLE, "kein Modell")), List.of(), () -> READ))
                .isInstanceOfSatisfying(CheckUnavailableException.class, e ->
                        assertThat(e.reason()).isEqualTo(CheckUnavailableException.Reason.NO_MODEL));
        assertThatThrownBy(() -> Workspace.checkResult(done(JobState.COMPLETED, new JobProgress(1, 1, 0, 0), null),
                List.of(event(StatementAnalysisJobs.CHECK_NOT_RELEASED, "1 Seiten ohne KI-Freigabe nicht geprüft")),
                () -> READ)).isInstanceOfSatisfying(CheckUnavailableException.class, e ->
                assertThat(e.reason()).isEqualTo(CheckUnavailableException.Reason.NOT_RELEASED));
        assertThatThrownBy(() -> Workspace.checkResult(done(JobState.COMPLETED, new JobProgress(1, 1, 0, 0), null),
                List.of(event(StatementAnalysisJobs.CHECK_NOT_RELEASED, "0 Seiten ohne KI-Freigabe nicht geprüft"),
                        event(StatementAnalysisJobs.CHECK_NOT_CURRENT, "1 Seiten ohne aktuelle Analyse nicht geprüft")),
                () -> READ)).isInstanceOfSatisfying(CheckUnavailableException.class, e ->
                assertThat(e.reason()).isEqualTo(CheckUnavailableException.Reason.NOT_CURRENT));
    }

    @Test
    void aCancelledOrPausedJobIsACancellationAndAFailedPageReadsTheAccessError() {
        assertThatThrownBy(() -> Workspace.checkResult(done(JobState.CANCELLED, JobProgress.NONE, null), List.of(),
                () -> READ)).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> Workspace.checkResult(done(JobState.PAUSED, JobProgress.NONE, null), List.of(),
                () -> READ)).isInstanceOf(CancellationException.class);
        JobEvent denied = new JobEvent("job", 2, Instant.EPOCH, JobType.STATEMENT_CHECK, "wiki", "DEV",
                JobEvent.Severity.WARNING, JobEvent.Category.ACCESS, StatementAnalysisJobs.COMPONENT, null,
                "PAGE_FAILED", "Eine Seite wurde nicht geprüft (AccessDeniedException)", null, null, null, null);
        assertThatThrownBy(() -> Workspace.checkResult(done(JobState.COMPLETED, new JobProgress(1, 1, 0, 1), null),
                List.of(denied), () -> {
                    throw new IllegalArgumentException("wie der Zugriffsfehler des Core");
                })).isInstanceOf(IllegalArgumentException.class);
    }

    private static JobStatus done(JobState state, JobProgress progress, JobError error) {
        return new JobStatus("job", JobType.STATEMENT_CHECK, "wiki", "DEV", "app", state, progress, error, false,
                Instant.EPOCH, null, null, "1", null, Map.of(), false, false, null);
    }

    private static JobEvent event(String code, String message) {
        return new JobEvent("job", 1, Instant.EPOCH, JobType.STATEMENT_CHECK, "wiki", "DEV", JobEvent.Severity.INFO,
                JobEvent.Category.JOB, StatementAnalysisJobs.COMPONENT, null, code, message, null, null, null, null);
    }
}
