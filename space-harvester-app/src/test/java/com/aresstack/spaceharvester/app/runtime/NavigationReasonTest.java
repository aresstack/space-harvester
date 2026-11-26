package com.aresstack.spaceharvester.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.ParityFtp;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.background.PageLoads;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Erwerbsgrund „Navigation“ (ADR 0037, Punkt 3): gemerkt für jede besuchte Ressource, die der Aufrufer lesen darf. */
class NavigationReasonTest {

    @TempDir
    Path data;

    @Test
    void visitedResourcesKeepTheReasonNavigationAcrossARestartButOnlyWhatTheCallerMayRead() throws Exception {
        try (ParityFtp server = ParityFtp.start()) {
            String readme;
            try (Harvester harvester = harvester(server)) {
                readme = harvester.sources().sources().stream().filter(source -> source.id().equals("ftp-test"))
                        .findFirst().orElseThrow().navigationPageId("/parity/README.txt").orElseThrow();
                String denied = harvester.sources().sources().stream().filter(source -> source.id().equals("ftp-test"))
                        .findFirst().orElseThrow().navigationPageId("/parity/notizen.txt").orElseThrow();
                Workspace workspace = new Workspace(harvester, new AccessGuard((context, scope, kind) ->
                        kind == AccessKind.CONTENT && denied.equals(scope.pageId())
                                ? AccessDecision.DENY : AccessDecision.ALLOW),
                        new ActivityLog(data.resolve("aktivitaet.txt")));

                workspace.acquireVisitedResources("ftp-test", List.of("/parity/README.txt", "/parity/notizen.txt"));

                assertThat(harvester.navigationMarks().pages("ftp-test", "ftp-test")).containsExactly(readme);
            }
            try (Harvester restarted = harvester(server)) {
                assertThat(restarted.navigationMarks().pages("ftp-test", "ftp-test")).containsExactly(readme);
            }
        }
    }

    @Test
    void revisitingAFolderChecksStoredResourcesForFreshnessAndStoresTheChangedVersion() throws Exception {
        try (ParityFtp server = ParityFtp.start(); Harvester harvester = harvester(server)) {
            String readme = harvester.sources().sources().stream().filter(source -> source.id().equals("ftp-test"))
                    .findFirst().orElseThrow().navigationPageId("/parity/README.txt").orElseThrow();
            Workspace workspace = new Workspace(harvester, new AccessGuard((context, scope, kind) ->
                    AccessDecision.ALLOW), new ActivityLog(data.resolve("aktivitaet.txt")));
            Ended ended = new Ended(harvester);

            awaitLoads(harvester, ended, workspace.acquireVisitedResources("ftp-test", List.of("/parity/README.txt")));
            String first = harvester.pages().cached(harvester.caller(), "ftp-test", readme).orElseThrow()
                    .snapshot().payload().rawSha256();

            server.change("/parity/README.txt", "Satz Delta nach der Aenderung im Wurzelordner.\n");
            awaitLoads(harvester, ended, workspace.acquireVisitedResources("ftp-test", List.of("/parity/README.txt")));

            assertThat(harvester.pages().cached(harvester.caller(), "ftp-test", readme).orElseThrow()
                    .snapshot().payload().rawSha256()).isNotEqualTo(first);
        }
    }

    /**
     * Wartet, bis die gestarteten Teile beendet sind und ihr Ende gemeldet ist: Erst dann sieht {@code PageLoads} sie
     * nicht mehr als ladend, und ein erneuter Besuch startet eine eigene Prüfung, statt sich dem endenden Teil
     * anzuschließen. Die Meldungen gehen in Anmeldereihenfolge an die Empfänger, {@code PageLoads} zuerst.
     */
    private static void awaitLoads(Harvester harvester, Ended ended, PageLoads.Joined loads) throws Exception {
        assertThat(loads).isNotNull();
        java.util.List<String> jobIds = loads.started().orElseThrow().jobIds();
        for (String jobId : jobIds) {
            JobStatus status = harvester.await(jobId, Duration.ofSeconds(60));
            assertThat(status.state()).as("Laden: %s", status.error()).isEqualTo(JobState.COMPLETED);
        }
        ended.await(jobIds, Duration.ofSeconds(60));
    }

    /** Gemeldete Enden der Teile zum Laden von Seiten. */
    private static final class Ended {
        private final java.util.Set<String> ids = new java.util.HashSet<>();

        Ended(Harvester harvester) {
            harvester.backgroundJobs().subscribe(PageLoads.KIND, status -> {
                if (!PageLoads.loading(status.state())) {
                    ended(status.id());
                }
            }, last -> ended(last.id()));
        }

        private synchronized void ended(String id) {
            ids.add(id);
            notifyAll();
        }

        synchronized void await(java.util.Collection<String> wanted, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!ids.containsAll(wanted)) {
                long left = deadline - System.nanoTime();
                assertThat(left).as("Ende gemeldet für %s", wanted).isPositive();
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }
    }

    private Harvester harvester(ParityFtp server) {
        Map<String, String> options = Map.of("host", server.host(), "port", String.valueOf(server.port()),
                "startPoints", "", "navigationFill", "true");
        return Harvester.builder().source(new SourceSettingsConfig("ftp-test", "ftp", "FTP test", null, null,
                options)).dataDirectory(data).build();
    }
}
