package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Finden mit mehreren Anfragen aus einer Frage und Ladereihenfolge nach Auszug (ADR 0013, Fortschreibung): Die Quelle
 * bekommt Begriffe statt eines Satzes, die Funde werden ohne Dopplung zusammengeführt, der Reranker ordnet nur das
 * Laden, die Treffer ordnet der Volltextindex über den Cache.
 */
class KeywordSearchDiscoveryTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final String LONG_REQUEST = "Wie wird in HaVdB der PoC mit Hashicorp Vault für PIMA beschrieben?";

    private final FakeSource wiki = new FakeSource();
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(wiki);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return wiki.id().equals(sourceId) ? Optional.of(wiki) : Optional.empty();
        }
    };
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    /** Was im Cache liegt; der Index liefert es in fester Reihenfolge, unabhängig von der Ladereihenfolge. */
    private final Set<String> cached = Collections.synchronizedSet(new TreeSet<>());
    private final List<List<String>> loads = Collections.synchronizedList(new ArrayList<>());
    private final PageTextIndex texts = mock(PageTextIndex.class);
    private final KeywordSearch.Prefetch prefetch = (caller, hits) -> {
        loads.add(hits.stream().map(KeywordSearch.Hit::pageId).toList());
        hits.forEach(hit -> cached.add(hit.pageId()));
        return KeywordSearch.Prefetch.Fill.NONE;
    };

    KeywordSearchDiscoveryTest() {
        when(texts.search(anyString(), any(), any(), anyInt())).thenAnswer(call -> {
            int limit = call.getArgument(3);
            return new ArrayList<>(cached).stream().limit(limit)
                    .map(id -> new PageTextIndex.Match("wiki", id, "DEV", "Seite " + id, "Auszug " + id)).toList();
        });
    }

    @Test
    void aLongQuestionBecomesSeveralSourceQueriesWhoseHitsAreMergedOnce() {
        wiki.results("HaVdB PoC PIMA", "1", "2").results("HaVdB", "2", "3").results("PoC", "3", "4")
                .results("Hashicorp", "1", "5");

        KeywordSearch.Results results = search(KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off())
                .search(APP, KeywordSearch.Query.of(LONG_REQUEST), null, 50);

        // Zuerst die wörtlichen Begriffe, dann die Schlüsselwörter; „wird“ oder „in“ sind keine Anfrage.
        assertThat(wiki.searches).containsExactlyInAnyOrder("HaVdB PoC PIMA", "HaVdB", "PoC", "Hashicorp");
        assertThat(loads).containsExactly(List.of("1", "2", "3", "4", "5"));
        // Im Cache sucht weiter die Frage selbst.
        verify(texts).search(org.mockito.ArgumentMatchers.eq(LONG_REQUEST), any(), any(), anyInt());
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2", "3", "4", "5");
        assertThat(results.complete()).isTrue();
        assertThat(results.nextCursor()).isNull();
    }

    @Test
    void aConciseQueryGoesToTheSourceUnchanged() {
        wiki.results("Hashicorp Vault", "1");

        KeywordSearch.Results results = search(KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off())
                .search(APP, KeywordSearch.Query.of("Hashicorp Vault"), null, 50);

        assertThat(wiki.searches).containsExactly("Hashicorp Vault");
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1");
    }

    @Test
    void withOneQueryEvenALongQuestionGoesUnchanged() {
        wiki.results(LONG_REQUEST, "7");

        search(1, ExcerptPriority.off()).search(APP, KeywordSearch.Query.of(LONG_REQUEST), null, 50);

        assertThat(wiki.searches).containsExactly(LONG_REQUEST);
    }

    @Test
    void theCursorPagesThroughTheMergedHitsWithoutRepeatingAny() {
        wiki.results("HaVdB PoC PIMA", "1", "2").results("HaVdB", "2", "3").results("PoC", "3", "4")
                .results("Hashicorp", "1", "5");
        KeywordSearch search = search(KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off());
        KeywordSearch.Query query = KeywordSearch.Query.of(LONG_REQUEST);

        List<String> delivered = new ArrayList<>();
        String cursor = null;
        for (int batch = 0; batch < 10; batch++) {
            KeywordSearch.Results results = search.search(APP, query, cursor, 2);
            results.hits().forEach(hit -> delivered.add(hit.pageId()));
            cursor = results.nextCursor();
            if (cursor == null) {
                break;
            }
        }

        assertThat(cursor).isNull();
        assertThat(delivered).containsExactlyInAnyOrder("1", "2", "3", "4", "5");
        assertThat(loads).containsExactly(List.of("1", "2"), List.of("3", "4"), List.of("5"));
    }

    @Test
    void aFailedQueryKeepsTheOthersButTheResultIsIncomplete() {
        wiki.results("HaVdB PoC PIMA", "1").results("HaVdB", "2").results("Hashicorp", "3");
        wiki.failing = "PoC";

        KeywordSearch.Results results = search(KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off())
                .search(APP, KeywordSearch.Query.of(LONG_REQUEST), null, 50);

        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2", "3");
        assertThat(results.sources()).extracting(KeywordSearch.SourceState::status)
                .containsExactly(KeywordSearch.SourceState.Status.SEARCHED);
        assertThat(results.complete()).isFalse();
    }

    @Test
    void excerptRankingChangesOnlyTheLoadOrder() {
        wiki.results("Urlaub", "a", "b", "c");
        List<String> queries = new ArrayList<>();
        ExcerptPriority excerpts = new ExcerptPriority((query, documents) -> {
            queries.add(query);
            return new double[] {0.1, 0.9, 0.5};
        }, "wiki"::equals);

        KeywordSearch.Results ranked = search(1, excerpts).search(APP, KeywordSearch.Query.of("Urlaub"), null, 50);
        List<List<String>> rankedLoads = List.copyOf(loads);
        loads.clear();
        cached.clear();
        KeywordSearch.Results plain = search(1, ExcerptPriority.off()).search(APP, KeywordSearch.Query.of("Urlaub"),
                null, 50);

        assertThat(queries).containsExactly("Urlaub");
        assertThat(rankedLoads).containsExactly(List.of("b", "c", "a"));
        assertThat(loads).containsExactly(List.of("a", "b", "c"));
        assertThat(ranked.hits()).isEqualTo(plain.hits());
        assertThat(ranked.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("a", "b", "c");
    }

    private KeywordSearch search(int maxQueries, ExcerptPriority excerpts) {
        return new KeywordSearch(catalog, access, texts, prefetch, maxQueries, excerpts);
    }

    /** Eine Quelle mit eigener Suche: feste Treffer je Anfrage, merkt sich jede Anfrage. */
    private static final class FakeSource implements HarvestSource {

        private final Map<String, List<String>> results = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> searches = Collections.synchronizedList(new ArrayList<>());
        private volatile String failing;

        FakeSource results(String query, String... pageIds) {
            results.put(query, List.of(pageIds));
            return this;
        }

        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public Set<SourceCapability> capabilities() {
            return Set.of(SourceCapability.PARTITIONS, SourceCapability.LIVE_SEARCH);
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, pageId, "Seite " + pageId, "DEV", null, "1", null, null,
                    new TreeMap<>());
        }

        @Override
        public List<SourceSearchMatch> searchPages(String text, int limit, SearchSpaces spaces) {
            searches.add(text);
            if (text.equals(failing)) {
                throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                        null);
            }
            return results.getOrDefault(text, List.of()).stream().limit(limit)
                    .map(id -> new SourceSearchMatch(id, "Seite " + id, "Auszug " + id, null, "1", null)).toList();
        }
    }
}
