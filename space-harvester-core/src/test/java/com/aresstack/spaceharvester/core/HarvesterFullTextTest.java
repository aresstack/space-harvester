package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.source.DocumentFixtures;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Volltextindex über den Cache (#180, ADR 0013): Quellen ohne eigene Suche (lokale Dateien) sucht der Index, sobald
 * ihre Seiten im Cache liegen; was die Quellensuche findet, wird vor der Suche im Cache geladen.
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

    /** Jeder Text zeigt in dieselbe Richtung; es zählt nur, ob ein Vektor da ist. */
    private static final EmbeddingProvider MODEL = new EmbeddingProvider() {
        @Override
        public String modelId() {
            return "achsen@1";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(text -> new float[] {1, 0}).toList();
        }
    };

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
    void officeAndPdfFilesAreFoundByTheirTextOnceCached() throws IOException {
        // #183: Text aus Word und PDF über Tika, wie im Enterprise AI Client.
        Files.write(files.resolve("handbuch.docx"), DocumentFixtures.docx("Notstromaggregat wöchentlich prüfen"));
        Files.write(files.resolve("plan.pdf"), DocumentFixtures.pdf("Schichtplan Kesselhaus"));
        try (Harvester harvester = files(Harvester.builder())) {
            harvester.pages().page(harvester.caller(), "docs", id("handbuch.docx"), false);
            harvester.pages().page(harvester.caller(), "docs", id("plan.pdf"), false);

            assertThat(harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Notstromaggregat"), null, 10).hits())
                    .extracting(KeywordSearch.Hit::pageId).containsExactly(id("handbuch.docx"));
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Kesselhaus"),
                    null, 10).hits()).extracting(KeywordSearch.Hit::pageId).containsExactly(id("plan.pdf"));
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
    void theSemanticSearchFindsLoadsAndEmbedsBeforeItSearchesWithoutAnySync() {
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).embeddings(MODEL).build()) {
            PageSearchQuery wiki = PageSearchQuery.of("wiki", List.of(), null, null, null, null, null, null,
                    SearchSort.TITLE);

            // ADR 0013, Punkt 3: ein Aufruf, ohne vorherige Schlagwortsuche und ohne Abgleich.
            RelevanceSearch.RelevanceResults results = harvester.relevance().search(harvester.caller(), wiki,
                    "Nachtlauf", 10);

            assertThat(results.hits()).extracting(hit -> hit.hit().page().pageId()).containsExactly("102");
            assertThat(results.reranker()).startsWith("embedding-cosine@");
            assertThat(results.complete()).isTrue();
        }
    }

    @Test
    void localFilesGetEmbeddingsWhenOpenedToo() {
        try (Harvester harvester = files(Harvester.builder().embeddings(MODEL))) {
            harvester.pages().page(harvester.caller(), "docs", id("betrieb.md"), false);
            PageSearchQuery docs = PageSearchQuery.of("docs", List.of(), null, null, null, null, null, null,
                    SearchSort.TITLE);

            assertThat(harvester.relevance().search(harvester.caller(), docs, "Nachtlauf", 10).hits())
                    .extracting(hit -> hit.hit().page().pageId()).containsExactly(id("betrieb.md"));
        }
    }

    @Test
    void foundPagesAppearAtOnceAndAreInTheCacheWhenTheSearchIsComplete() throws InterruptedException {
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).build()) {
            KeywordSearch.Query query = KeywordSearch.Query.of("Nachtlauf");
            KeywordSearch.Results first = harvester.keywordSearch().search(harvester.caller(), query, null, 10);

            // Sofort da, ohne aufs Laden zu warten: der Fund der Quelle mit Titel und Auszug.
            assertThat(first.ranked()).extracting(KeywordSearch.Hit::pageId).containsExactly("102");
            assertThat(first.progress()).isNotNull();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!harvester.keywordSearch().progress(first.progress().id()).orElseThrow().done()
                    && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }

            assertThat(harvester.keywordSearch().search(harvester.caller(), query, null, 10).hits())
                    .extracting(KeywordSearch.Hit::pageId).containsExactly("102");
            assertThat(cached(harvester)).isTrue();
        }
    }

    @Test
    void withoutPrefetchTheSearchLoadsNothingAndFindsNothingNew() {
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).prefetchSearchHits(false)
                .build()) {
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Nachtlauf"),
                    null, 10).hits()).isEmpty();

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
        // Die Tests prüfen die Treffer nach dem Laden; die App wartet nicht, sondern folgt dem Fortschritt. Die Suche
        // wartet, bis der Stapel geladen ist; die Grenze gilt nur gegen Hänger, unter CI-Last darf Laden länger dauern.
        return builder.searchWait(Duration.ofSeconds(120)).source(new SourceSettingsConfig("docs", "files", "Dokumente", null, null,
                Map.of("directory", files.toString()))).dataDirectory(data).build();
    }

    private static SourceSettingsConfig wiki() {
        URI base = SERVER.baseUrl();
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", base, SourceSettingsConfig.Auth.anonymous(),
                Map.of("connectTimeoutMillis", "2000"));
    }
}
