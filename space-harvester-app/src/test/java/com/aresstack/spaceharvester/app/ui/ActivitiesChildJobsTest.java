package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.jobs.ChildJobs;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Klammer mit Unteraufträgen (Angelo 27.11.2026): unter „Buch schreiben“ je Quelle eine Zeile mit ihrem Titel. */
class ActivitiesChildJobsTest {

    @Test
    void theSourcesOfABookStandUnderItsRowWithTheirTitles() {
        JobStatus book = ActivitiesBackgroundTabTest.job("b1", JobType.BOOK, null, JobState.RUNNING, Map.of(), 9,
                new JobProgress(7, 2, 2, 0));
        JobStatus first = ActivitiesBackgroundTabTest.job("c1", JobType.BOOK, null, JobState.COMPLETED,
                Map.of(ChildJobs.PARENT_PARAMETER, "b1", ChildJobs.STEP_PARAMETER, "Streuobst im Jahreslauf"), 8,
                new JobProgress(1, 1, 1, 0));
        JobStatus second = ActivitiesBackgroundTabTest.job("c2", JobType.BOOK, null, JobState.RUNNING,
                Map.of(ChildJobs.PARENT_PARAMETER, "b1", ChildJobs.STEP_PARAMETER, "Mahd und Beweidung"), 7,
                new JobProgress(1, 0, 0, 0));
        JobStatus alone = ActivitiesBackgroundTabTest.job("x", JobType.EXPORT, "OMEGA", JobState.COMPLETED,
                Map.of(), 6, new JobProgress(1, 1, 1, 0));
        List<JobStatus> jobs = List.of(book, first, second, alone);

        assertThat(ActivitiesView.children(jobs).get("b1")).extracting(JobStatus::id).containsExactlyInAnyOrder("c1",
                "c2");
        assertThat(ActivitiesView.grouped(jobs, Set.of("b1"))).as("zugeklappt").extracting(JobStatus::id)
                .containsExactly("b1", "x");
        assertThat(Texts.type(second)).isEqualTo("Mahd und Beweidung");
        assertThat(Texts.type(book)).isEqualTo("Buch schreiben");
    }
}
