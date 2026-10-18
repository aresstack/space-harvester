package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schlagwortsuche (ADR 0010) gegen eine Fake-Confluence über echtes HTTP: Treffer aus der Suche der Quelle (Titel und
 * Inhalt), ohne vorherigen Abgleich und ohne Nachladen.
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
    void findsContentInANeverSyncedSourceByLoadingTheFoundPagesIntoTheCacheFirst() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            assertThat(harvester.sources().sources().getFirst().capabilities()).contains(SourceCapability.LIVE_SEARCH);

            KeywordSearch.Results results = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 50);

            // ADR 0013: Die Quelle findet, der Cache liefert; Space aus den live gelesenen Kopfdaten.
            assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId, KeywordSearch.Hit::spaceKey,
                    KeywordSearch.Hit::title).containsExactlyInAnyOrder(tuple("102", "DEV", "Prüfung"),
                    tuple("200", "OPS", "Bereitschaft"));
            assertThat(results.hits()).filteredOn(hit -> hit.pageId().equals("102")).singleElement()
                    .satisfies(hit -> {
                        assertThat(hit.parentPageId()).isEqualTo("100");
                        assertThat(hit.snippet()).contains("Nachtlauf startet um zwei");
                    });
            assertThat(results.sources()).containsExactly(new KeywordSearch.SourceState("wiki",
                    KeywordSearch.SourceState.Status.SEARCHED, null));
            assertThat(results.loading()).isZero();
            assertThat(results.nextCursor()).isNull();
            assertThat(results.complete()).isTrue();
            // Kein Abgleich: genau die gefundenen Seiten liegen jetzt im Cache, die Startseite nicht.
            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 10).pages())
                    .extracting(page -> page.header().key().pageId()).containsExactly("102");
        }
    }

    @Test
    void withoutLoadingTheSearchFindsOnlyWhatIsAlreadyCached() {
        try (Harvester harvester = harvester(Harvester.builder().prefetchSearchHits(false))) {
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Nachtlauf"), null,
                    50).hits()).isEmpty();

            harvester.pages().page(harvester.caller(), "wiki", "200", false);

            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Nachtlauf"), null,
                    50).hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("200");
        }
    }

    @Test
    void moreHitsComeWithTheCursor() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            KeywordSearch.Results first = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 1);
            KeywordSearch.Results second = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), first.nextCursor(), 1);

            assertThat(first.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("102");
            assertThat(first.complete()).isFalse();
            assertThat(second.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("200");
            assertThat(second.nextCursor()).isNull();
            assertThat(second.complete()).isTrue();
            assertThatThrownBy(() -> harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), "k1.kaputt!", 1)).isInstanceOf(InvalidQueryException.class);
            // Ein Cursor gilt nur für die Anfrage, aus der er stammt.
            assertThatThrownBy(() -> harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Bereitschaft"), first.nextCursor(), 1))
                    .isInstanceOf(InvalidQueryException.class);
        }
    }

    @Test
    void spaceFiltersApplyToTheHitsOfTheSource() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            KeywordSearch.Results results = harvester.keywordSearch().search(harvester.caller(),
                    new KeywordSearch.Query("Nachtlauf", "wiki", Set.of(), Set.of("OPS")), null, 50);

            assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("200");
        }
    }

    @Test
    void spaceFiltersAndExclusionsGoIntoTheQueryOfTheSource() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            KeywordSearch.Results only = harvester.keywordSearch().search(harvester.caller(),
                    new KeywordSearch.Query("Nachtlauf", "wiki", Set.of(), Set.of("OPS")), null, 50);
            String onlyCql = lastCql();
            KeywordSearch.Results without = harvester.keywordSearch().search(harvester.caller(),
                    new KeywordSearch.Query("Nachtlauf", "wiki", Set.of(), Set.of(), Set.of("DEV")), null, 50);
            String withoutCql = lastCql();

            // In der CQL, nicht erst nachträglich gefiltert: Auswahl als space in (…), Ausschluss wie im Enterprise AI
            // Client als space not in (…).
            assertThat(onlyCql).contains("space in (\"OPS\")").doesNotContain("not in");
            assertThat(withoutCql).contains("space not in (\"DEV\")");
            assertThat(only.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("200");
            assertThat(without.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("200");
        }
    }

    private static String lastCql() {
        URI search = CONFLUENCE.requests().reversed().stream()
                .filter(uri -> uri.getPath().endsWith("/search")).findFirst().orElseThrow();
        String query = search.getRawQuery();
        for (String pair : query.split("&")) {
            if (pair.startsWith("cql=")) {
                return URLDecoder.decode(pair.substring(4), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("keine CQL in " + search);
    }

    @Test
    void hitsTheCallerMayNotSeeNeverAppear() {
        try (Harvester harvester = harvester(Harvester.builder().accessPolicy((context, scope, kind) ->
                "102".equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW))) {
            KeywordSearch.Results results = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 50);

            assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("200");
        }
    }

    @Test
    void aPageCachedForOneCallerStaysHiddenFromAnotherWithoutContentRights() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Nachtlauf"), null,
                    50).hits()).hasSize(2);
        }
        // Derselbe Cache, ein Aufrufer ohne Inhaltsrecht an 102: Fundstelle und Auszug stammen aus dem Inhalt.
        try (Harvester harvester = harvester(Harvester.builder().prefetchSearchHits(false).accessPolicy(
                (context, scope, kind) -> kind == AccessKind.CONTENT && "102".equals(scope.pageId())
                        ? AccessDecision.DENY : AccessDecision.ALLOW))) {
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Nachtlauf"), null,
                    50).hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("200");
        }
    }

    @Test
    void anUnreachableSourceDoesNotDiscardTheOthers() {
        try (Harvester harvester = harvester(Harvester.builder().source(new SourceSettingsConfig("down",
                "confluence", "Weg", java.net.URI.create("http://127.0.0.1:1"), SourceSettingsConfig.Auth.anonymous(),
                Map.of("connectTimeoutMillis", "500"))))) {
            KeywordSearch.Results results = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 50);

            assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactlyInAnyOrder("102", "200");
            assertThat(results.sources()).extracting(KeywordSearch.SourceState::sourceId,
                    KeywordSearch.SourceState::status).containsExactlyInAnyOrder(
                    tuple("wiki", KeywordSearch.SourceState.Status.SEARCHED),
                    tuple("down", KeywordSearch.SourceState.Status.UNAVAILABLE));
            assertThat(results.complete()).isFalse();

            // Auch über „Weitere laden“ bleibt die ausgefallene Quelle als Lücke sichtbar.
            KeywordSearch.Results first = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 1);
            KeywordSearch.Results last = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), first.nextCursor(), 1);
            // Beide liegen schon im Cache; der Cache ordnet, keine Seite kommt doppelt.
            assertThat(List.of(first.hits().getFirst().pageId(), last.hits().getFirst().pageId()))
                    .containsExactlyInAnyOrder("102", "200");
            assertThat(last.nextCursor()).isNull();
            assertThat(last.complete()).isFalse();
        }
    }

    private Harvester harvester(Harvester.Builder builder) {
        return builder
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build();
    }
}
