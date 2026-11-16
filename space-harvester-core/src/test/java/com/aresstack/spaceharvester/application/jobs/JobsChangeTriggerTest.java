package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR 0030: Unter welchem Auslöser ein Auftrag in den Cache schreibt. */
class JobsChangeTriggerTest {

    @Test
    void theTriggerOnTheJobWins() {
        assertThat(Jobs.trigger(JobType.SYNC, Map.of(ChangeTrigger.PARAMETER, "SCHEDULE")))
                .isEqualTo(ChangeTrigger.SCHEDULE);
        assertThat(Jobs.trigger(JobType.EXPORT, Map.of(ChangeTrigger.PARAMETER, "manual")))
                .isEqualTo(ChangeTrigger.MANUAL);
    }

    @Test
    void withoutOneExportsAreExportAndHarvestsAndImportsManual() {
        for (JobType type : new JobType[] {JobType.EXPORT, JobType.ASSETS, JobType.ATTACHMENTS, JobType.CONVERSION,
                JobType.METADATA_EXPORT, JobType.ANALYTICS_EXPORT, JobType.KNOWLEDGE_GRAPH_EXPORT}) {
            assertThat(Jobs.trigger(type, Map.of())).as(type.name()).isEqualTo(ChangeTrigger.EXPORT);
        }
        assertThat(Jobs.trigger(JobType.HARVEST, Map.of())).isEqualTo(ChangeTrigger.MANUAL);
        assertThat(Jobs.trigger(JobType.IMPORT, null)).isEqualTo(ChangeTrigger.MANUAL);
    }

    @Test
    void aSyncWithoutATriggerIsUnknownNeverBackground() {
        assertThat(Jobs.trigger(JobType.SYNC, Map.of())).isEqualTo(ChangeTrigger.UNKNOWN);
    }
}
