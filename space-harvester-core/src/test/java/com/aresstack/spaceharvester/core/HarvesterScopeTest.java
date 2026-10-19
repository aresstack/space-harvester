package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.sync.SyncSummary;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startpunkte und Tiefe einer Confluence-Quelle gelten auch beim Abgleich (wie die Indexierung im Enterprise AI
 * Client): Seiten außerhalb fallen aus dem Katalog wie gelöschte, ohne Startpunkte bleibt alles wie bisher.
 */
class HarvesterScopeTest {

    private FakeConfluence confluence;
    private FakeConfluenceServer server;

    @TempDir
    Path data;

    @BeforeEach
    void startServer() throws Exception {
        confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        confluence.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        confluence.page("102", "Kapitel", "DEV", "<p>Kapitel</p>").storage("<p>Kapitel</p>");
        confluence.page("103", "Abschnitt", "DEV", "<p>Abschnitt</p>").storage("<p>Abschnitt</p>");
        confluence.child("100", "102");
        confluence.child("102", "103");
        confluence.homepage("DEV", "100");
        server = new FakeConfluenceServer(confluence);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void withoutStartPointsTheWholeSpaceStays() throws Exception {
        try (Harvester harvester = harvester(Map.of())) {
            SyncSummary first = sync(harvester);
            SyncSummary second = sync(harvester);
            assertThat(first.generation()).isNotNull();
            assertThat(second.generation()).isNotNull();
            assertThat(second.removed()).isZero();
            assertThat(pageIds(harvester)).containsExactlyInAnyOrder("100", "102", "103");
        }
    }

    @Test
    void pagesBeyondTheStartPointsAndDepthLeaveTheCatalog() throws Exception {
        try (Harvester harvester = harvester(Map.of())) {
            assertThat(sync(harvester).generation()).isNotNull();
        }
        try (Harvester harvester = harvester(Map.of("startPoints", "page:100", "maxDepth", "1"))) {
            SyncSummary summary = sync(harvester);
            assertThat(summary.removed()).isEqualTo(1);
            assertThat(summary.withheld()).isZero();
            assertThat(summary.generation()).isNotNull();
            assertThat(pageIds(harvester)).containsExactlyInAnyOrder("100", "102");
        }
    }

    @Test
    void aFailedDiscoveryRemovesNothing() throws Exception {
        try (Harvester harvester = harvester(Map.of())) {
            assertThat(sync(harvester).generation()).isNotNull();
        }
        try (Harvester harvester = harvester(Map.of("startPoints", "page:100", "maxDepth", "1"))) {
            confluence.failNext(500, 100);
            JobStatus sync = harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30));
            SyncSummary summary = harvester.syncs().status(sync.id(), Harvester.OWNER).summary();
            assertThat(summary == null || summary.generation() == null).isTrue();
            assertThat(summary == null ? 0 : summary.removed()).isZero();
        }
        try (Harvester harvester = harvester(Map.of())) {
            assertThat(pageIds(harvester)).containsExactlyInAnyOrder("100", "102", "103");
        }
    }

    private SyncSummary sync(Harvester harvester) throws Exception {
        JobStatus sync = harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30));
        assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
        return harvester.syncs().status(sync.id(), Harvester.OWNER).summary();
    }

    private static List<String> pageIds(Harvester harvester) {
        return harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 100).pages().stream()
                .map(page -> page.header().key().pageId()).toList();
    }

    private Harvester harvester(Map<String, String> options) {
        Map<String, String> all = new LinkedHashMap<>(options);
        all.put("connectTimeoutMillis", "2000");
        all.put("maxRetries", "0");
        return Harvester.builder()
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", server.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), all))
                .dataDirectory(data)
                .build();
    }
}
