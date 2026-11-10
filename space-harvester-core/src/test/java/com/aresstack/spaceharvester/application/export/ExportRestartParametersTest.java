package com.aresstack.spaceharvester.application.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ein Wiederanlauf nach einem Neustart bearbeitet denselben Umfang wie der ursprüngliche Export. */
class ExportRestartParametersTest {

    private static JobStatus stored(String rootPageId, Map<String, String> parameters) {
        return new JobStatus("e1", JobType.EXPORT, "confluence", "DEV", "client-a", JobState.PAUSED,
                JobProgress.NONE, null, false, Instant.parse("2026-11-10T15:00:00Z"), null, null, rootPageId, null,
                parameters, false, false);
    }

    @Test
    void depthFormatsAndFreshnessOfASubtreeExportComeBack() {
        ExportRequest original = ExportRequest.of("confluence", "DEV", "client-a", List.of("json", "md"))
                .withRefresh(true).withRoot("100").withDepth(0);

        Map<String, String> parameters = Exports.parameters(original);
        ExportRequest replay = Exports.request(stored("100", parameters));

        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(Map.of("depth", "0", "refresh", "true",
                "formats", "json,md"));
        assertThat(replay.sourceId()).isEqualTo("confluence");
        assertThat(replay.spaceKey()).isEqualTo("DEV");
        assertThat(replay.owner()).isEqualTo("client-a");
        assertThat(replay.rootPageId()).isEqualTo("100");
        assertThat(replay.depth()).isZero();
        assertThat(replay.refresh()).isTrue();
        assertThat(replay.formats()).containsExactly("json", "md");
    }

    @Test
    void aSpaceExportFromBeforeTheParametersRestartsAsAPlainSpaceExport() {
        ExportRequest replay = Exports.request(stored(null, Map.of()));

        assertThat(replay.rootPageId()).isNull();
        assertThat(replay.depth()).isNull();
        assertThat(replay.refresh()).isFalse();
        assertThat(replay.formats()).isEmpty();
        assertThat(Exports.parameters(ExportRequest.of("confluence", "DEV", "client-a", List.of()))).isEmpty();
    }
}
