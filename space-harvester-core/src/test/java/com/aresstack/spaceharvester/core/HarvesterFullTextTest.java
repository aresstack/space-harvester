package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Volltextindex über den Cache (#180): Quellen ohne eigene Suche (lokale Dateien) sucht der Index, sobald ihre Seiten
 * im Cache liegen; Treffer der Live-Suche lädt ein begrenzter Hintergrund in den Cache.
 */
class HarvesterFullTextTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("102", "Prüfung", "DEV", "<p>Nachtlauf startet um zwei</p>")
                .storage("<p>Nachtlauf startet um zwei</p>");
        CONFLUENCE.searchResults("102");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;
    @TempDir
    Path files;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @BeforeEach
    void writeFiles() throws IOException {
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n\nDer Nachtlauf startet um zwei Uhr.\n",
                StandardCharsets.UTF_8);
        Files.writeString(files.resolve("urlaub.txt"), "Urlaubsplanung für das Team", StandardCharsets.UTF_8);
    }

    @Test
    void sourcesWithoutOwnSearchAreSearchedInTheIndexOnceTheirPagesAreCached() {
        try (Harvester harvester = files(Harvester.builder())) {
            KeywordSearch.Results before = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 10);
            assertThat(before.hits()).isEmpty();
            assertThat(before.sources()).containsExactly(new KeywordSearch.SourceState("docs",
                    KeywordSearch.SourceState.Status.INDEXED, null));

            harvester.pages().page(harvester.caller(), "docs", id("betrieb.md"), false);
            KeywordSearch.Results after = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("nachtlauf"), null, 10);

            assertThat(after.hits()).extracting(KeywordSearch.Hit::sourceId, KeywordSearch.Hit::pageId)
                    .containsExactly(tuple("docs", id("betrieb.md")));
            assertThat(after.hits().getFirst().snippet()).contains("Der Nachtlauf startet um zwei Uhr.");
            assertThat(after.complete()).isTrue();
        }
    }

    @Test
    void aSyncFillsTheIndexAndIndexHitsNeedContentRights() throws Exception {
        try (Harvester harvester = files(Harvester.builder())) {
            harvester.await(harvester.sync("docs", "docs").id(), Duration.ofSeconds(30));
            awaitEnrichments(harvester);

            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Urlaubsplanung"),
                    null, 10).hits()).extracting(KeywordSearch.Hit::pageId).containsExactly(id("urlaub.txt"));
        }
        try (Harvester harvester = files(Harvester.builder().accessPolicy((context, scope, kind) ->
                kind == AccessKind.CONTENT && scope.pageId() != null ? AccessDecision.DENY : AccessDecision.ALLOW))) {
            // Wer nur Kopfdaten sehen darf, findet die Seite nicht über ihren Inhalt.
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Urlaubsplanung"),
                    null, 10).hits()).isEmpty();
        }
    }

    @Test
    void visibleLiveHitsAreLoadedIntoTheCacheInTheBackground() throws InterruptedException {
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).build()) {
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Nachtlauf"),
                    null, 10).hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("102");

            Instant deadline = Instant.now().plusSeconds(10);
            while (!cached(harvester) && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
            assertThat(cached(harvester)).isTrue();
        }
    }

    @Test
    void withoutPrefetchTheSearchLoadsNothing() throws InterruptedException {
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).prefetchSearchHits(false)
                .build()) {
            harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Nachtlauf"), null, 10);
            Thread.sleep(300);

            assertThat(cached(harvester)).isFalse();
        }
    }

    private static boolean cached(Harvester harvester) {
        try {
            return !harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 10).pages().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void awaitEnrichments(Harvester harvester) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var enrichments = harvester.jobs().list(Harvester.OWNER, JobType.ENRICHMENT);
            if (!enrichments.isEmpty() && enrichments.stream().allMatch(job -> job.finishedAt() != null)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Anreicherung läuft nach 30 s noch");
    }

    private static String id(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    private Harvester files(Harvester.Builder builder) {
        return builder.source(new SourceSettingsConfig("docs", "files", "Dokumente", null, null,
                Map.of("directory", files.toString()))).dataDirectory(data).build();
    }

    private static SourceSettingsConfig wiki() {
        URI base = SERVER.baseUrl();
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", base, SourceSettingsConfig.Auth.anonymous(),
                Map.of("connectTimeoutMillis", "2000"));
    }
}
