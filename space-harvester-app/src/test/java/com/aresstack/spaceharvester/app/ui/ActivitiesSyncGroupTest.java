package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Abgleich einer ganzen Quelle (Angelo 22.11.2026): eine aufklappbare Zeile „Abgleich der Quelle“ über den Abgleichen
 * je Space, sofort ab dem ersten gestarteten Space. Nur erfundene Quellen und Spaces.
 */
class ActivitiesSyncGroupTest {

    private static JobStatus sync(String id, String space, JobState state, String group, long minute,
                                  JobProgress progress) {
        return ActivitiesBackgroundTabTest.job(id, JobType.SYNC, space, state,
                group == null ? Map.of() : Map.of(SpaceSyncJobs.GROUP, group), minute, progress);
    }

    @Test
    void theSyncsOfAWholeSourceStandUnderOneRowThatSumsThem() {
        List<JobStatus> jobs = List.of(
                ActivitiesBackgroundTabTest.job("e1", JobType.EXPORT, "OMEGA", JobState.COMPLETED, Map.of(), 9,
                        new JobProgress(8, 8, 8, 0)),
                sync("s3", "GAMMA", JobState.QUEUED, "g1", 3, new JobProgress(0, 0, 0, 0)),
                sync("s2", "BETA", JobState.COMPLETED, "g1", 2, new JobProgress(17, 17, 17, 0)),
                sync("s1", "ALPHA", JobState.RUNNING, "g1", 1, new JobProgress(264, 53, 53, 0)),
                sync("einzeln", "DELTA", JobState.RUNNING, null, 0, new JobProgress(10, 1, 1, 0)));

        List<JobStatus> visible = ActivitiesView.visible(jobs, false);
        String row = ActivitiesView.SYNC_GROUP_PREFIX + "g1";
        assertThat(visible).extracting(JobStatus::id).containsExactly("e1", row, "s3", "s2", "s1", "einzeln");
        assertThat(ActivitiesView.grouped(visible, Set.of())).extracting(JobStatus::id)
                .containsExactly("e1", row, "s3", "s2", "s1", "einzeln");
        assertThat(ActivitiesView.grouped(visible, Set.of(row))).as("zugeklappt").extracting(JobStatus::id)
                .containsExactly("e1", row, "einzeln");
        assertThat(ActivitiesView.children(visible).get(row)).extracting(JobStatus::id)
                .containsExactly("s3", "s2", "s1");

        JobStatus source = visible.get(1);
        assertThat(ActivitiesView.groupRow(source)).isTrue();
        assertThat(ActivitiesView.syncGroupRow(source)).isTrue();
        assertThat(Texts.type(source)).isEqualTo("Abgleich der Quelle");
        assertThat(source.state()).isEqualTo(JobState.RUNNING);
        assertThat(source.progress()).isEqualTo(new JobProgress(281, 70, 70, 0));
        assertThat(source.partitionKey()).isNull();
        assertThat(ActivitiesView.visible(jobs, true)).as("nicht im Reiter Hintergrund").isEmpty();
    }

    @Test
    void theRowStandsAsSoonAsTheFirstSpaceStartedNotOnlyWhenAllRun() {
        // Angelo 22.11.2026: Die Klammer fehlte, während die übrigen Spaces noch eingereiht wurden.
        List<JobStatus> visible = ActivitiesView.visible(List.of(
                sync("s1", "ALPHA", JobState.RUNNING, "g1", 1, new JobProgress(5, 1, 1, 0))), false);

        assertThat(ActivitiesView.grouped(visible, Set.of())).extracting(JobStatus::id)
                .containsExactly(ActivitiesView.SYNC_GROUP_PREFIX + "g1", "s1");
    }

    @Test
    void aSingleSyncWithoutGroupStaysAPlainRow() {
        List<JobStatus> jobs = List.of(sync("s1", "ALPHA", JobState.RUNNING, null, 1, new JobProgress(5, 1, 1, 0)));

        assertThat(ActivitiesView.visible(jobs, false)).extracting(JobStatus::id).containsExactly("s1");
        assertThat(Texts.type(jobs.get(0))).isEqualTo("Abgleich");
    }
}
