package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Suche als Beschaffung (ADR 0010) gegen eine Fake-Confluence über echtes HTTP: Treffer aus der Suche der Quelle
 * (Titel und Inhalt), ohne vorherigen Abgleich, nachgeladen und danach im Index.
 */
class HarvesterSearchTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("OPS", "Betrieb");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.page("102", "Prüfung", "DEV", "<p>Nachtlauf startet um zwei</p>")
                .storage("<p>Nachtlauf startet um zwei</p>");
        CONFLUENCE.page("200", "Bereitschaft", "OPS", "<p>Nachtlauf überwachen</p>")
                .storage("<p>Nachtlauf überwachen</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.homepage("DEV", "100");
        // Die Quelle findet „Nachtlauf“ im Inhalt, nicht im Titel.
        CONFLUENCE.searchResults("102", "200");
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
    void findsContentInANeverSyncedSourceAndKeepsTheHitsInTheIndex() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            assertThat(harvester.sources().sources().getFirst().capabilities()).contains(SourceCapability.LIVE_SEARCH);

            SearchResults results = harvester.search().search(harvester.caller(), query("wiki", List.of(),
                    "Nachtlauf"), null, 50);

            assertThat(results.live()).isTrue();
            assertThat(results.nextCursor()).isNull();
            assertThat(results.hits()).extracting(SearchHit::page).extracting(page -> page.header().key().pageId())
                    .containsExactly("102", "200");
            assertThat(harvester.pages().page(harvester.caller(), "wiki", "102", false).live()).isFalse();
        }
    }

    @Test
    void spaceFiltersApplyToTheHitsOfTheSource() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            SearchResults results = harvester.search().search(harvester.caller(), query("wiki", List.of("OPS"),
                    "Nachtlauf"), null, 50);

            assertThat(results.hits()).extracting(hit -> hit.page().header().key().pageId()).containsExactly("200");
        }
    }

    @Test
    void hitsTheCallerMayNotReadNeverAppear() {
        try (Harvester harvester = harvester(Harvester.builder().accessPolicy((context, scope, kind) ->
                "102".equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW))) {
            SearchResults results = harvester.search().search(harvester.caller(), query(null, List.of(),
                    "Nachtlauf"), null, 50);

            assertThat(results.hits()).extracting(hit -> hit.page().header().key().pageId()).containsExactly("200");
        }
    }

    @Test
    void withoutTextTheSearchStaysInTheIndex() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            harvester.search().search(harvester.caller(), query("wiki", List.of(), "Nachtlauf"), null, 50);

            SearchResults results = harvester.search().search(harvester.caller(), query("wiki", List.of(), null),
                    null, 50);

            assertThat(results.live()).isFalse();
            assertThat(results.hits()).extracting(hit -> hit.page().header().key().pageId())
                    .containsExactlyInAnyOrder("102", "200");
        }
    }

    private static PageSearchQuery query(String sourceId, List<String> spaces, String text) {
        return PageSearchQuery.of(sourceId, List.of(), spaces, text, null, null, null, null, null, SearchSort.TITLE);
    }

    private Harvester harvester(Harvester.Builder builder) {
        return builder
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build();
    }
}
