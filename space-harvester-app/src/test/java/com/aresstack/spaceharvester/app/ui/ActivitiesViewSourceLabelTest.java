package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ActivitiesViewSourceLabelTest {

    @Test
    void allSourceJobsDoNotDisplayNullAsAChildPartition() {
        assertThat(ActivitiesView.where(status(JobType.PURGE, null))).isEqualTo("bscw-bund");
        assertThat(ActivitiesView.where(status(JobType.EXPORT, "bscw-bund")))
                .isEqualTo("bscw-bund / bscw-bund");
    }

    private static JobStatus status(JobType type, String partition) {
        return new JobStatus("test", type, "bscw-bund", partition, null, JobState.COMPLETED,
                JobProgress.NONE, null, false, Instant.EPOCH, null, null);
    }
}
