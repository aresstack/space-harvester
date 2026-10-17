package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Bibliothek ohne Spring und ohne den Dienst (#100): Abgleich eines Space aus einer Fake-Confluence über echtes
 * HTTP in den Lucene-Cache, Lesen aus dem Cache und Export, alles über {@link Harvester}.
 */
class HarvesterTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.page("102", "Prüfung", "DEV", "<p>Inhalt</p>").storage("<p>Inhalt</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void harvestsAndExportsASpaceWithoutSpring() throws Exception {
        try (Harvester harvester = harvester()) {
            assertThat(harvester.sources().partitions("wiki")).extracting(Partition::key).containsExactly("DEV");

            JobStatus sync = harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            assertThat(harvester.syncs().status(sync.id(), Harvester.OWNER).summary()).isNotNull();

            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 100).pages())
                    .extracting(page -> page.header().key().pageId()).containsExactlyInAnyOrder("100", "102");
            assertThat(harvester.pages().page(harvester.caller(), "wiki", "102", false).snapshot().payload()
                    .bytes()).isNotEmpty();
            assertThat(harvester.pages().tree(harvester.caller(), "wiki", "DEV").tree()).isNotNull();

            JobStatus export = harvester.await(harvester.export("wiki", "DEV", List.of("markdown")).id(),
                    Duration.ofSeconds(30));
            assertThat(export.state()).isEqualTo(JobState.COMPLETED);
            ExportArtifact artifact = harvester.exportArtifact(export.id()).orElseThrow();
            assertThat(artifact.complete()).isTrue();
            assertThat(artifact.files()).isNotEmpty();
        }
    }

    @Test
    void exportsANeverSyncedSpaceToWordWithoutAPriorSync() throws Exception {
        try (Harvester harvester = harvester()) {
            JobStatus export = harvester.await(harvester.export("wiki", "DEV", List.of("docx")).id(),
                    Duration.ofSeconds(30));

            assertThat(export.state()).isEqualTo(JobState.COMPLETED);
            assertThat(export.progress().written()).isEqualTo(2);
            ExportArtifact artifact = harvester.exportArtifact(export.id()).orElseThrow();
            assertThat(artifact.complete()).isTrue();
            ExportArtifact.File docx = artifact.files().stream().filter(file -> file.name().endsWith(".docx"))
                    .findFirst().orElseThrow();
            try (java.io.InputStream in = harvester.exports().artifactFile(export.id(), Harvester.OWNER,
                    docx.name()).orElseThrow()) {
                byte[] bytes = in.readAllBytes();
                // DOCX ist ein ZIP-Archiv.
                assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
            }
            // Der Cache ist nebenbei gefüllt: Seiten lesen geht jetzt ohne eigenen Abgleich.
            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 100).pages())
                    .extracting(page -> page.header().key().pageId()).containsExactlyInAnyOrder("100", "102");
        }
    }

    @Test
    void readsANeverSyncedPageFromTheSourceAndKeepsItInTheCache() throws Exception {
        try (Harvester harvester = harvester()) {
            DeliveredPage first = harvester.pages().page(harvester.caller(), "wiki", "102", false);
            assertThat(first.snapshot().payload().bytes()).isNotEmpty();

            DeliveredPage second = harvester.pages().page(harvester.caller(), "wiki", "102", false);
            assertThat(second.live()).isFalse();
            assertThat(second.refresh()).isNull();
            assertThat(second.snapshot().payload().rawSha256()).isEqualTo(first.snapshot().payload().rawSha256());
        }
    }

    @Test
    void reopensTheCacheOfAnEarlierRun() throws Exception {
        try (Harvester harvester = harvester()) {
            harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30));
        }
        try (Harvester harvester = harvester()) {
            List<ListedPage> pages = harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 100).pages();
            assertThat(pages).hasSize(2);
        }
    }

    @Test
    void theConfiguredPolicyGuardsSyncAndExport() {
        try (Harvester harvester = harvester(Harvester.builder()
                .accessPolicy((context, scope, kind) -> kind == AccessKind.METADATA ? AccessDecision.ALLOW
                        : AccessDecision.DENY))) {
            assertThatThrownBy(() -> harvester.sync("wiki", "DEV")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> harvester.export("wiki", "DEV", List.of()))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    private Harvester harvester() {
        return harvester(Harvester.builder());
    }

    private Harvester harvester(Harvester.Builder builder) {
        return builder
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build();
    }
}
