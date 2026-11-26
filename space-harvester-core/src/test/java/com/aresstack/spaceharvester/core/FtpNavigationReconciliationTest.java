package com.aresstack.spaceharvester.core;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.ParityFtp;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FtpNavigationReconciliationTest {

    @TempDir
    Path data;

    @Test
    void fullInventoryDoesNotDiscardAnIndependentlyAcquiredPageOutsideItsStartPoints() throws Exception {
        try (ParityFtp server = ParityFtp.start();
             Harvester harvester = harvester(server, "/parity/docs")) {
            String pageId = harvester.sources().sources().stream()
                    .filter(source -> source.id().equals("ftp-test")).findFirst().orElseThrow()
                    .navigationPageId("/parity/README.txt").orElseThrow();
            assertThat(pageId).isEqualTo(pageId(server, "parity/README.txt"));
            BackgroundGroup group = harvester.pageLoads()
                    .load(harvester.caller(), "FTP navigation", List.of(new PageKey("ftp-test", pageId)))
                    .orElseThrow();

            for (String jobId : group.jobIds()) {
                assertThat(harvester.await(jobId, Duration.ofSeconds(60)).state())
                        .isEqualTo(JobState.COMPLETED);
            }
            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", pageId)).isPresent();

            assertThat(harvester.await(harvester.sync("ftp-test", "ftp-test").id(),
                    Duration.ofSeconds(60)).state()).isEqualTo(JobState.COMPLETED);
            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", pageId)).isPresent();

            assertThat(harvester.await(harvester.sync("ftp-test", "ftp-test").id(),
                    Duration.ofSeconds(60)).state()).isEqualTo(JobState.COMPLETED);
            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", pageId)).isPresent();
        }
    }

    @Test
    void navigationOnlySourceDoesNotNeedAnInventoryStartPoint() throws Exception {
        try (ParityFtp server = ParityFtp.start(); Harvester harvester = harvester(server, "")) {
            String pageId = harvester.sources().sources().stream()
                    .filter(source -> source.id().equals("ftp-test")).findFirst().orElseThrow()
                    .navigationPageId("/parity/README.txt").orElseThrow();
            BackgroundGroup group = harvester.pageLoads()
                    .load(harvester.caller(), "FTP navigation", List.of(new PageKey("ftp-test", pageId)))
                    .orElseThrow();
            for (String jobId : group.jobIds()) {
                assertThat(harvester.await(jobId, Duration.ofSeconds(60)).state())
                        .isEqualTo(JobState.COMPLETED);
            }
            assertThat(harvester.await(harvester.sync("ftp-test", "ftp-test").id(),
                    Duration.ofSeconds(60)).state()).isEqualTo(JobState.COMPLETED);
            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", pageId)).isPresent();
        }
    }

    /** ADR 0037, Punkt 3: Bereinigen bietet Erworbenes ohne Grund an, entfernt es erst auf Wahl, Navigation bleibt. */
    @Test
    void cleanupOffersOnlyPagesWithoutReasonAndRemovesThemOnlyWhenChosen() throws Exception {
        try (ParityFtp server = ParityFtp.start();
             Harvester harvester = harvester(server, "/parity/docs")) {
            var source = harvester.sources().sources().stream()
                    .filter(candidate -> candidate.id().equals("ftp-test")).findFirst().orElseThrow();
            String readme = source.navigationPageId("/parity/README.txt").orElseThrow();
            String notes = source.navigationPageId("/parity/notizen.txt").orElseThrow();
            BackgroundGroup group = harvester.pageLoads().load(harvester.caller(), "FTP navigation",
                    List.of(new PageKey("ftp-test", readme), new PageKey("ftp-test", notes))).orElseThrow();
            for (String jobId : group.jobIds()) {
                assertThat(harvester.await(jobId, Duration.ofSeconds(60)).state()).isEqualTo(JobState.COMPLETED);
            }
            harvester.navigationMarks().mark("ftp-test", "ftp-test", List.of(notes));
            assertThat(harvester.await(harvester.sync("ftp-test", "ftp-test").id(),
                    Duration.ofSeconds(60)).state()).isEqualTo(JobState.COMPLETED);

            var previews = harvester.cleanupPreviews("ftp-test");

            assertThat(previews).hasSize(1);
            assertThat(previews.get(0).problem()).isNull();
            // Erstes Bereinigen: Was ohne Grund dasteht, kam vor dem Merken der Gründe, also „Grund unbekannt“.
            assertThat(previews.get(0).unknown()).containsExactly(readme);
            assertThat(previews.get(0).withoutReason()).isEmpty();
            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", readme)).isPresent();

            assertThat(harvester.await(harvester.cleanUp("ftp-test", "ftp-test", java.util.Set.of(readme, notes))
                    .id(), Duration.ofSeconds(60)).state()).isEqualTo(JobState.COMPLETED);

            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", readme)).isEmpty();
            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", notes)).isPresent();
            assertThat(harvester.cleanupPreviews("ftp-test").get(0).unknown()).isEmpty();
        }
    }

    private Harvester harvester(ParityFtp server, String scope) {
        Map<String, String> options = Map.of("host", server.host(), "port", String.valueOf(server.port()),
                "startPoints", scope, "navigationFill", "true");
        return Harvester.builder().source(new SourceSettingsConfig("ftp-test", "ftp",
                "FTP test", null, null, options)).dataDirectory(data).build();
    }

    private static String pageId(ParityFtp server, String path) {
        String resourceId = "ftp:" + server.host() + ":" + server.port() + "/" + path;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(resourceId.getBytes(StandardCharsets.UTF_8));
    }
}