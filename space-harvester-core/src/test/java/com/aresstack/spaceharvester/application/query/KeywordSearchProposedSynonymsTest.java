package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.acquisition.FindingCatalog;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * K5a, Auflagen 2, 4 und 5 des Architekten: Treffer, deren einziger Textgrund ein Synonymvorschlag ist, stehen in jedem
 * Weg hinter den übrigen, auch über zwei Quellen und mit mehreren Treffern je Anfrage; die Grenze
 * {@link SearchExpansion#MAX_EXPANSIONS} gilt erst nach dem Entdoppeln; ein Cursor ohne die Methode gilt nicht mit ihr.
 * Erfundene Begriffe.
 */
class KeywordSearchProposedSynonymsTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");

    private final FakeSource first = new FakeSource("wiki-a");
    private final FakeSource second = new FakeSource("wiki-b");
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(first, second);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return all().stream().filter(source -> source.id().equals(sourceId)).findFirst();
        }
    };
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    /** „Wolkenpegel“ hat nur in wiki-b das bestätigte Synonym „Schleierlinie“. */
    private final SearchExpansion confirmed = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
            "Wolkenpegel".equals(term) && "wiki-b".equals(sourceId)
                    ? List.of(new SearchExpansion.Counterpart("Schleierlinie", "synonym")) : List.of());
    /** Vorgeschlagen ist überall „Dunsthöhe“. */
    private final SearchExpansion proposed = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
            "Wolkenpegel".equals(term) ? List.of(new SearchExpansion.Counterpart("Dunsthöhe",
                    HitReason.PROPOSED_SYNONYM)) : List.of());

    KeywordSearchProposedSynonymsTest() {
        first.results("Wolkenpegel", "a1").results("Dunsthöhe", "a2", "a3");
        second.results("Wolkenpegel", "b1", "b2").results("Schleierlinie", "b4").results("Dunsthöhe", "b3");
    }

    private KeywordSearch search(PageTextIndex texts, KeywordSearch.Prefetch prefetch, SearchExpansion confirmed,
                                 SearchExpansion proposed) {
        return new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, FindingCatalog.NONE,
                () -> true, KeywordSearch.BeforeSearch.NONE, confirmed, proposed);
    }

    private static KeywordSearch.Query query() {
        return KeywordSearch.Query.of("Wolkenpegel").withGlossarySynonyms(true);
    }

    @Test
    void withoutCacheProposalOnlyHitsOfBothSourcesComeLast() {
        KeywordSearch.Results results = search(null, null, confirmed, proposed).search(APP, query(), null, 50);

        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId)
                .containsExactly("a1", "b1", "b4", "b2", "a2", "a3", "b3");
    }

    @Test
    void inTheCacheProposalOnlyHitsComeLastAcrossSources() {
        // Der Index findet je Anfrage Seiten beider Quellen; Erweiterungen nur in ihrer Quelle.
        Map<String, List<String>> index = Map.of(
                "Wolkenpegel", List.of("wiki-a/a1", "wiki-b/b1", "wiki-b/b2"),
                "Dunsthöhe", List.of("wiki-a/a2", "wiki-a/a3", "wiki-b/b3"),
                "Schleierlinie", List.of("wiki-b/b4"));
        PageTextIndex texts = mock(PageTextIndex.class);
        when(texts.search(anyString(), any(), any(), anyInt())).thenAnswer(call -> {
            String text = call.getArgument(0);
            Collection<String> sources = call.getArgument(1);
            return index.getOrDefault(text, List.of()).stream()
                    .filter(key -> sources.contains(key.substring(0, key.indexOf('/'))))
                    .map(key -> {
                        String sourceId = key.substring(0, key.indexOf('/'));
                        String pageId = key.substring(key.indexOf('/') + 1);
                        return new PageTextIndex.Match(sourceId, pageId, "DEV", "Seite " + pageId, "Auszug");
                    }).toList();
        });

        KeywordSearch.Results results = search(texts, (caller, hits) -> KeywordSearch.Prefetch.Fill.NONE, confirmed,
                proposed).search(APP, query(), null, 50);

        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId)
                .containsExactly("a1", "b1", "b2", "b4", "a2", "a3", "b3");
    }

    @Test
    void theLimitCountsOnlyProposalsThatAreNotAlreadyAsked() {
        // Fünf Vorschläge, zwei davon stellt die bestätigte Beziehung schon: drei bleiben, keiner fällt der Grenze zum
        // Opfer, weil erst nach dem Entdoppeln gekappt wird.
        SearchExpansion twoConfirmed = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
                "Wolkenpegel".equals(term) ? List.of(new SearchExpansion.Counterpart("Schleierlinie", "synonym"),
                        new SearchExpansion.Counterpart("Nebelmaß", "synonym")) : List.of());
        SearchExpansion five = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
                "Wolkenpegel".equals(term) ? List.of("Schleierlinie", "Nebelmaß", "Dunsthöhe", "Taugrenze",
                        "Hauchmarke").stream().map(text -> new SearchExpansion.Counterpart(text,
                        HitReason.PROPOSED_SYNONYM)).toList() : List.of());
        KeywordSearch search = search(null, null, twoConfirmed, five);

        search.search(APP, new KeywordSearch.Query("Wolkenpegel", "wiki-a", Set.of(), Set.of(), Set.of())
                .withGlossarySynonyms(true), null, 50);

        assertThat(first.searches).containsExactlyInAnyOrder("Wolkenpegel", "Schleierlinie", "Nebelmaß",
                "Dunsthöhe", "Taugrenze", "Hauchmarke");
        assertThat(search.glossarySynonymsWanted(APP, query())).isEqualTo(3);
        assertThat(search.glossarySynonymsWanted(APP, KeywordSearch.Query.of("Wolkenpegel"))).isZero();
    }

    @Test
    void theHintCountsPerSourceLikeTheSearch() {
        // Global gäbe es keine Dubletten; in wiki-b stellt die bestätigte Beziehung „Schleierlinie“ schon.
        SearchExpansion four = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
                "Wolkenpegel".equals(term) && sourceId != null ? List.of("Schleierlinie", "Dunsthöhe", "Taugrenze",
                        "Hauchmarke").stream().map(text -> new SearchExpansion.Counterpart(text,
                        HitReason.PROPOSED_SYNONYM)).toList() : List.of());
        KeywordSearch search = search(null, null, confirmed, four);

        // wiki-a: vier Vorschläge; wiki-b: drei nach dem Entdoppeln. Der Hinweis nennt das Höchste je Quelle.
        assertThat(search.glossarySynonymsWanted(APP, query())).isEqualTo(4);
        assertThat(search.glossarySynonymsWanted(APP, new KeywordSearch.Query("Wolkenpegel", "wiki-b", Set.of(),
                Set.of(), Set.of()).withGlossarySynonyms(true))).isEqualTo(3);
    }

    @Test
    void aCursorWithoutTheMethodIsNotValidWithIt() {
        first.results("Wolkenpegel", "a1", "a5", "a6");
        KeywordSearch search = search(null, null, confirmed, proposed);
        String cursor = search.search(APP, KeywordSearch.Query.of("Wolkenpegel"), null, 1).nextCursor();

        assertThat(cursor).isNotNull();
        assertThatThrownBy(() -> search.search(APP, query(), cursor, 1)).isInstanceOf(InvalidQueryException.class);
    }

    /** Eine Quelle mit eigener Suche: feste Treffer je Anfrage, merkt sich jede Anfrage. */
    private static final class FakeSource implements HarvestSource {

        private final String id;
        private final Map<String, List<String>> results = new ConcurrentHashMap<>();
        private final List<String> searches = java.util.Collections.synchronizedList(new ArrayList<>());

        FakeSource(String id) {
            this.id = id;
        }

        FakeSource results(String query, String... pageIds) {
            results.put(query, List.of(pageIds));
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String title() {
            return id;
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
            return results.getOrDefault(text, List.of()).stream().limit(limit)
                    .map(page -> new SourceSearchMatch(page, "Seite " + page, "Auszug " + page, null, "1", null))
                    .toList();
        }
    }
}
