package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.acquisition.FindingCatalog;
import com.aresstack.spaceharvester.application.searchrun.InMemorySearchRunLog;
import com.aresstack.spaceharvester.application.searchrun.SearchRun;
import com.aresstack.spaceharvester.application.searchrun.SearchRunId;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
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
    void reSortingTheSameSearchOffersNoFindingTwiceForCataloguing() {
        // Fundstelle 23, Abnahme PR 4: Neusortieren derselben Suche (searchId) bietet nur Neues an.
        wiki.results("Wolkenpegel", "1", "2");
        List<List<String>> offered = Collections.synchronizedList(new ArrayList<>());
        FindingCatalog recording = (finder, sourceId, findings) -> offered.add(findings.stream()
                .map(FindingCatalog.Finding::pageId).toList());
        KeywordSearch search = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, recording);
        KeywordSearch.Query query = KeywordSearch.Query.of("Wolkenpegel");

        search.search(APP, query, null, 50, null, null, "suche-1");
        search.search(APP, query, null, 50, null, null, "suche-1");
        search.search(APP, query, null, 50, null, null, "suche-2");

        assertThat(offered).as("je Suche einmal").containsExactly(List.of("1", "2"), List.of("1", "2"));
    }

    @Test
    void aSubtreeBoundaryKeepsFindsOutsideFromCataloguingLoadingAndTheIndexList() {
        // Angelo 26.11.2026 (#1506): Die Teilbaum-Grenze greift vor Katalogisieren und Laden, nicht erst an der Liste.
        wiki.results("Wolkenpegel", "1", "2", "3");
        cached.add("9");
        List<List<String>> offered = Collections.synchronizedList(new ArrayList<>());
        FindingCatalog recording = (finder, sourceId, findings) -> offered.add(findings.stream()
                .map(FindingCatalog.Finding::pageId).toList());
        KeywordSearch search = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, recording);
        // Bekannt ist Seite 1 (Katalog), die Grenze lässt dazu Seite 2 zu (etwa ein Dokument nur in der Quelle).
        SearchBoundary boundary = new SearchBoundary() {
            @Override
            public boolean admits(com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                return key.sourceId().equals("wiki") && key.pageId().equals("2");
            }

            @Override
            public boolean reaches(String sourceId) {
                return sourceId.equals("wiki");
            }

            @Override
            public String fingerprint() {
                return "teilbaum";
            }
        };
        KeywordSearch.Query query = new KeywordSearch.Query("Wolkenpegel", "wiki", Set.of(), Set.of())
                .bounded(PageSet.of(Map.of("wiki", List.of("1"))), boundary);

        KeywordSearch.Results results = search.search(APP, query, null, 50, null, null, "suche-1");

        assertThat(offered).containsExactly(List.of("1", "2"));
        assertThat(loads).containsExactly(List.of("1", "2"));
        assertThat(results.ranked()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2");
        assertThat(query.within(PageSet.of(Map.of("wiki", List.of("1")))).admits("wiki", "2"))
                .as("Eingrenzen auf Kandidaten hebt die Grenze auf").isFalse();
        assertThat(query.withText("anders").admits("wiki", "2")).isTrue();
    }

    @Test
    void whatTheCatalogDidNotAcceptIsOfferedAgainOnReSorting() {
        // Abnahme #1082, Punkt 1: Nur Angenommenes gilt als angeboten; Neusortieren holt den Rest nach.
        wiki.results("Wolkenpegel", "1", "2");
        List<List<String>> offered = Collections.synchronizedList(new ArrayList<>());
        FindingCatalog full = new FindingCatalog() {
            @Override
            public void offer(AccessContext finder, String sourceId, List<Finding> findings) {
                accept(finder, sourceId, findings);
            }

            @Override
            public Set<String> accept(AccessContext finder, String sourceId, List<Finding> findings) {
                offered.add(findings.stream().map(Finding::pageId).toList());
                // Beim ersten Mal ist nur für eine Seite Platz.
                return offered.size() == 1 ? Set.of(findings.getFirst().pageId()) : Set.of();
            }
        };
        KeywordSearch search = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, full);
        KeywordSearch.Query query = KeywordSearch.Query.of("Wolkenpegel");

        search.search(APP, query, null, 50, null, null, "suche-1");
        search.search(APP, query, null, 50, null, null, "suche-1");

        assertThat(offered).containsExactly(List.of("1", "2"), List.of("2"));
    }

    @Test
    void confirmedRelationsAddQueriesPerSourceAndInTheCache() {
        // #336, Teil 4: „RSL“ fragt die Quelle auch nach „Rückmeldeschlüssel“; die Erweiterung sieht Quelle und Space.
        List<String> scopes = Collections.synchronizedList(new ArrayList<>());
        SearchExpansion expansion = new SearchExpansion((caller, term, sourceId, spaceKey) -> {
            scopes.add(term + "@" + sourceId + "/" + spaceKey);
            return "RSL".equals(term) ? List.of("Rückmeldeschlüssel") : List.of();
        });
        wiki.results("RSL", "1").results("Rückmeldeschlüssel", "2", "1");

        KeywordSearch.Results results = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, FindingCatalog.NONE,
                () -> true, KeywordSearch.BeforeSearch.NONE, expansion)
                .search(APP, new KeywordSearch.Query("RSL", "wiki", Set.of(), Set.of("DEV"), Set.of()), null, 50);

        assertThat(wiki.searches).containsExactlyInAnyOrder("RSL", "Rückmeldeschlüssel");
        assertThat(scopes).contains("RSL@wiki/DEV");
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2");
        // Im Cache wird die Erweiterung nur in dieser Quelle gesucht.
        verify(texts).search(org.mockito.ArgumentMatchers.eq("Rückmeldeschlüssel"),
                org.mockito.ArgumentMatchers.eq(Set.of("wiki")), any(), anyInt());
    }

    private KeywordSearch withGlossarySynonyms() {
        // K5a: „Wolkenpegel“ hat das bestätigte Synonym „Schleierlinie“ und das vorgeschlagene „Dunsthöhe“; die
        // Methode bringt nur das vorgeschlagene, und „Schleierlinie“ käme sonst doppelt.
        SearchExpansion confirmed = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
                "Wolkenpegel".equals(term) ? List.of(new SearchExpansion.Counterpart("Schleierlinie", "synonym"))
                        : List.of());
        SearchExpansion proposed = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
                "Wolkenpegel".equals(term) ? List.of(new SearchExpansion.Counterpart("Dunsthöhe", "synonym-proposed"),
                        new SearchExpansion.Counterpart("Schleierlinie", "synonym-proposed")) : List.of());
        wiki.results("Wolkenpegel", "1").results("Schleierlinie", "2").results("Dunsthöhe", "3");
        return new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, FindingCatalog.NONE,
                () -> true, KeywordSearch.BeforeSearch.NONE, confirmed, proposed);
    }

    @Test
    void withoutTheGlossarySynonymsMethodTheSearchStaysExactlyTheSame() {
        KeywordSearch.Results results = withGlossarySynonyms()
                .search(APP, new KeywordSearch.Query("Wolkenpegel", "wiki", Set.of(), Set.of("DEV"), Set.of()), null,
                        50);

        assertThat(wiki.searches).containsExactlyInAnyOrder("Wolkenpegel", "Schleierlinie");
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2");
    }

    @Test
    void theGlossarySynonymsMethodAddsOnlyProposalsAfterTheConfirmedOnes() {
        KeywordSearch.Results results = withGlossarySynonyms()
                .search(APP, new KeywordSearch.Query("Wolkenpegel", "wiki", Set.of(), Set.of("DEV"), Set.of())
                        .withGlossarySynonyms(true), null, 50);

        assertThat(wiki.searches).containsExactlyInAnyOrder("Wolkenpegel", "Schleierlinie", "Dunsthöhe");
        // Treffer nur über das vorgeschlagene Synonym ranken hinter dem bestätigten.
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2", "3");
        // Jeder Grund nur einmal: das bestätigte Synonym nicht noch einmal als Vorschlag.
        assertThat(results.hits()).flatExtracting(KeywordSearch.Hit::reasons).extracting(HitReason::relation)
                .doesNotContain(new HitReason.Relation("Wolkenpegel", "Schleierlinie", "synonym-proposed"));
        assertThat(results.hits().get(2).reasons()).extracting(HitReason::relation)
                .contains(new HitReason.Relation("Wolkenpegel", "Dunsthöhe", "synonym-proposed"));
    }

    @Test
    void eachHitCarriesTheQueriesThatReallyFoundItWithTheRelationAndTheWordsOfTheIndex() {
        // R1: Treffergrund aus dem Lauf der Suche; „RSL“ wird über eine bestätigte Abkürzung ausgeweitet.
        SearchExpansion expansion = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
                "RSL".equals(term) ? List.of(new SearchExpansion.Counterpart("Rückmeldeschlüssel", "abbreviation"))
                        : List.of());
        wiki.results("RSL", "1").results("Rückmeldeschlüssel", "2");
        when(texts.search(org.mockito.ArgumentMatchers.eq("RSL"), any(), any(), anyInt())).thenReturn(List.of(
                new PageTextIndex.Match("wiki", "1", "DEV", "RSL", "Auszug").withWords(
                        new PageTextIndex.Words(List.of("rsl"), List.of()))));
        when(texts.search(org.mockito.ArgumentMatchers.eq("Rückmeldeschlüssel"), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new PageTextIndex.Match("wiki", "2", "DEV", "B", "Auszug").withWords(
                                new PageTextIndex.Words(List.of(), List.of("rückmeldeschlüssel"))),
                        new PageTextIndex.Match("wiki", "1", "DEV", "RSL", "Auszug").withWords(
                                new PageTextIndex.Words(List.of(), List.of("rückmeldeschlüssel")))));

        KeywordSearch.Results results = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, FindingCatalog.NONE,
                () -> true, KeywordSearch.BeforeSearch.NONE, expansion)
                .search(APP, new KeywordSearch.Query("RSL", "wiki", Set.of(), Set.of("DEV"), Set.of()), null, 50);

        HitReason.Relation abbreviation = new HitReason.Relation("RSL", "Rückmeldeschlüssel", "abbreviation");
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2");
        assertThat(results.hits().get(0).reasons()).containsExactly(
                HitReason.text("RSL", null, List.of("rsl"), List.of()),
                HitReason.text("Rückmeldeschlüssel", abbreviation, List.of(), List.of("rückmeldeschlüssel")));
        assertThat(results.hits().get(1).reasons()).containsExactly(
                HitReason.text("Rückmeldeschlüssel", abbreviation, List.of(), List.of("rückmeldeschlüssel")));
        assertThat(results.hits().get(1).reasons().getFirst().original()).isFalse();
    }

    @Test
    void wordsFoundInAnotherDocumentOfThePageAreNotAttributedToTheShownOne() {
        // R1 (Codex-Befund #993): Die Seite selbst passt auf „RSL“, ein Anhang auf die Ausweitung. Gezeigt wird die
        // Seite; die Ausweitung bleibt Ursache, aber ohne die Wörter des Anhangs.
        SearchExpansion expansion = SearchExpansion.typed((caller, term, sourceId, spaceKey) ->
                "RSL".equals(term) ? List.of(new SearchExpansion.Counterpart("Rückmeldeschlüssel", "abbreviation"))
                        : List.of());
        wiki.results("RSL", "1");
        when(texts.search(org.mockito.ArgumentMatchers.eq("RSL"), any(), any(), anyInt())).thenReturn(List.of(
                new PageTextIndex.Match("wiki", "1", "DEV", "Seite", "Auszug").withWords(
                        new PageTextIndex.Words(List.of(), List.of("rsl")))));
        when(texts.search(org.mockito.ArgumentMatchers.eq("Rückmeldeschlüssel"), any(), any(), anyInt()))
                .thenReturn(List.of(new PageTextIndex.Match("wiki", "1", "DEV", "", "Auszug", "att", "liste.pdf")
                        .withWords(new PageTextIndex.Words(List.of(), List.of("rückmeldeschlüssel")))));

        KeywordSearch.Results results = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.topN(KeywordSearch.MAX_LIMIT), SearchRunLog.NONE, FindingCatalog.NONE,
                () -> true, KeywordSearch.BeforeSearch.NONE, expansion)
                .search(APP, new KeywordSearch.Query("RSL", "wiki", Set.of(), Set.of("DEV"), Set.of()), null, 50);

        assertThat(results.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.attachment()).isNull();
            assertThat(hit.reasons()).containsExactly(HitReason.text("RSL", null, List.of(), List.of("rsl")),
                    HitReason.text("Rückmeldeschlüssel", new HitReason.Relation("RSL", "Rückmeldeschlüssel",
                            "abbreviation"), List.of(), List.of()));
        });
    }

    @Test
    void aProvisionalHitNamesTheSourceQueryWithoutWords() {
        // R1: Was nur die Quelle meldet, trägt keine erfundene Fundstelle.
        wiki.results("Hashicorp Vault", "9");
        when(texts.search(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        KeywordSearch.Results results = search(KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off())
                .search(APP, KeywordSearch.Query.of("Hashicorp Vault"), null, 50);

        assertThat(results.found()).singleElement().satisfies(hit -> assertThat(hit.reasons())
                .containsExactly(HitReason.source("Hashicorp Vault", null)));
    }

    @Test
    void aConciseQueryGoesToTheSourceUnchangedFirstThenWithRuleVariantsToFetchMore() {
        // Angelo 27.11.2026: Die Online-Suche sammelt nur Seiten ein; Varianten ohne Modell holen auch Seiten, die
        // nicht alle Begriffe enthalten. Treffer kommen weiter nur aus dem lokalen Index.
        wiki.results("Hashicorp Vault", "1");

        KeywordSearch.Results results = search(KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off())
                .search(APP, KeywordSearch.Query.of("Hashicorp Vault"), null, 50);

        assertThat(wiki.searches).containsExactlyInAnyOrder("Hashicorp Vault", "Hashicorp", "Vault");
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

    @Test
    void allFindsComeAtOnceWithTheirSnippetsButOnlyTheBestLoad() {
        wiki.results("agil", "a", "b", "c", "d", "e");
        ExcerptPriority excerpts = new ExcerptPriority((query, documents) -> new double[] {0.1, 0.2, 0.9, 0.3, 0.8},
                "wiki"::equals);

        KeywordSearch.Results results = new KeywordSearch(catalog, access, texts, prefetch, 1, excerpts, 2)
                .search(APP, KeywordSearch.Query.of("agil"), null, 50);

        // Nur die beiden besten nach Titel und Auszug laden; der Rest ist sofort vorläufig sichtbar.
        assertThat(loads).containsExactly(List.of("c", "e"));
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("c", "e");
        assertThat(results.found()).extracting(KeywordSearch.Hit::pageId).containsExactly("d", "b", "a");
        assertThat(results.found()).extracting(KeywordSearch.Hit::snippet).containsExactly("Auszug d", "Auszug b",
                "Auszug a");
        assertThat(results.ranked()).extracting(KeywordSearch.Hit::pageId).containsExactly("c", "e", "d", "b", "a");
    }

    @Test
    void aLoadedFindMovesForwardInsteadOfDisappearing() {
        wiki.results("agil", "a", "b", "c");
        KeywordSearch.Prefetch nothingYet = (caller, hits) -> new KeywordSearch.Prefetch.Fill(hits, 0, "p",
                new KeywordSearch.Progress("p", hits.size(), 0, 0));
        KeywordSearch search = new KeywordSearch(catalog, access, texts, nothingYet, 1, ExcerptPriority.off(), 3);

        KeywordSearch.Results first = search.search(APP, KeywordSearch.Query.of("agil"), null, 50);
        cached.add("c");
        KeywordSearch.Results second = search.search(APP, KeywordSearch.Query.of("agil"), null, 50);

        assertThat(first.ranked()).extracting(KeywordSearch.Hit::pageId).containsExactly("a", "b", "c");
        assertThat(first.progress()).isEqualTo(new KeywordSearch.Progress("p", 3, 0, 0));
        assertThat(second.ranked()).extracting(KeywordSearch.Hit::pageId).containsExactly("c", "a", "b");
    }

    @Test
    void withoutSourceWideGrantFindsAreCheckedPerPage() {
        wiki.results("agil", "a", "b");
        AccessGuard perSpace = new AccessGuard((context, scope, kind) -> scope.pageId() != null
                && scope.spaceKey() == null ? AccessDecision.DENY : AccessDecision.ALLOW);

        KeywordSearch.Results results = new KeywordSearch(catalog, perSpace, texts, prefetch, 1,
                ExcerptPriority.off(), 50).search(APP, KeywordSearch.Query.of("agil"), null, 50);

        // Mit Space aus den live gelesenen Kopfdaten.
        assertThat(loads).containsExactly(List.of("a", "b"));
        assertThat(results.hits()).extracting(KeywordSearch.Hit::spaceKey).containsExactly("DEV", "DEV");
    }

    @Test
    void withoutContentRightsProvisionalFindsComeWithoutTheSourceExcerpt() {
        wiki.results("agil", "a");
        // Kopfdaten ja, Inhalt nein, und keine quellweite Freigabe: der Auszug stammt aus dem Inhalt.
        AccessGuard metadataOnly = new AccessGuard((context, scope, kind) -> kind == AccessKind.METADATA
                && (scope.pageId() == null || scope.spaceKey() != null) ? AccessDecision.ALLOW : AccessDecision.DENY);

        KeywordSearch.Results results = new KeywordSearch(catalog, metadataOnly, texts, prefetch, 1,
                ExcerptPriority.off(), 50).search(APP, KeywordSearch.Query.of("agil"), null, 50);

        assertThat(results.found()).extracting(KeywordSearch.Hit::pageId).containsExactly("a");
        assertThat(results.found()).extracting(KeywordSearch.Hit::snippet).containsExactly("");
    }

    @Test
    void aDelegatedUserGetsOnlyTheFinalListCheckedPageByPage() {
        wiki.results("Hashicorp Vault", "1", "2", "3", "4", "5");
        // Der Benutzer darf nur 2 und 4 sehen; jede Rückfrage an die Quelle wird gezählt.
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        AccessGuard guard = new AccessGuard(new com.aresstack.spaceharvester.application.access.AccessPolicy() {
            @Override
            public AccessDecision decide(AccessContext context, com.aresstack.spaceharvester.application.access
                    .ResourceScope scope, AccessKind kind) {
                if (scope.pageId() == null) {
                    return AccessDecision.ALLOW;
                }
                asked.add(scope.pageId());
                return Set.of("2", "4").contains(scope.pageId()) ? AccessDecision.ALLOW : AccessDecision.DENY;
            }

            @Override
            public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
                return AccessDecision.DENY;
            }
        });
        AccessContext user = AccessContext.endUser("u", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "t"));

        KeywordSearch.Results results = new KeywordSearch(catalog, guard, texts, prefetch,
                KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off())
                .search(user, KeywordSearch.Query.of("Hashicorp Vault"), null, 3);

        // Die Funde der Quelle laden ungeprüft; der Benutzer sieht sie nicht als vorläufige Treffer.
        assertThat(loads).containsExactly(List.of("1", "2", "3"));
        assertThat(results.found()).isEmpty();
        // Geprüft wird nur die Liste aus dem Cache, jede Seite einmal je Art.
        assertThat(new TreeSet<>(asked)).containsExactly("1", "2", "3");
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("2");
        // Der Cursor nennt keine ungeprüften Seiten.
        String cursor = new String(java.util.Base64.getUrlDecoder().decode(results.nextCursor()
                .substring(3)), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(cursor).doesNotContain("\np\t");
    }

    @Test
    void everyBatchIsARunWithSourcesAndCountsPerStage() {
        wiki.results("HaVdB PoC PIMA", "1", "2").results("HaVdB", "2", "3").results("PoC", "3", "4")
                .results("Hashicorp", "1", "5");
        InMemorySearchRunLog log = new InMemorySearchRunLog();
        KeywordSearch search = new KeywordSearch(catalog, access, texts, prefetch, KeywordSearch.DEFAULT_MAX_QUERIES,
                ExcerptPriority.off(), KeywordSearch.DEFAULT_LOADS, log);

        KeywordSearch.Results results = search.search(APP, KeywordSearch.Query.of(LONG_REQUEST), null, 50);

        SearchRun run = log.find(new SearchRunId(results.searchRun())).orElseThrow();
        assertThat(run.kind()).isEqualTo(SearchRun.Kind.KEYWORD);
        assertThat(run.owner()).isEqualTo(APP.jobOwner());
        assertThat(run.query()).isEqualTo(LONG_REQUEST);
        assertThat(run.partOf()).isNull();
        assertThat(run.sources()).containsExactly(new SearchRun.SourceOutcome("wiki",
                SearchRun.SourceOutcome.Status.SEARCHED, null));
        assertThat(run.count(SearchRun.Stage.QUERIES)).hasValue(4);
        assertThat(run.count(SearchRun.Stage.DISCOVERED)).hasValue(5);
        assertThat(run.count(SearchRun.Stage.LOAD_QUEUED)).hasValue(5);
        assertThat(run.count(SearchRun.Stage.PENDING)).hasValue(0);
        assertThat(run.count(SearchRun.Stage.CONFIRMED)).hasValue(5);
        assertThat(run.count(SearchRun.Stage.DELIVERED)).hasValue(5);
        assertThat(run.complete()).isTrue();
        assertThat(run.duration().isNegative()).isFalse();
    }

    @Test
    void aDelegatedUsersRunCountsOnlyTheCheckedFinalList() {
        wiki.results("Hashicorp Vault", "1", "2", "3");
        AccessGuard guard = new AccessGuard(new com.aresstack.spaceharvester.application.access.AccessPolicy() {
            @Override
            public AccessDecision decide(AccessContext context, com.aresstack.spaceharvester.application.access
                    .ResourceScope scope, AccessKind kind) {
                return scope.pageId() == null || "2".equals(scope.pageId()) ? AccessDecision.ALLOW
                        : AccessDecision.DENY;
            }

            @Override
            public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
                return AccessDecision.DENY;
            }
        });
        AccessContext user = AccessContext.endUser("u", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "t"));
        InMemorySearchRunLog log = new InMemorySearchRunLog();

        KeywordSearch.Results results = new KeywordSearch(catalog, guard, texts, prefetch,
                KeywordSearch.DEFAULT_MAX_QUERIES, ExcerptPriority.off(), KeywordSearch.DEFAULT_LOADS, log)
                .search(user, KeywordSearch.Query.of("Hashicorp Vault"), null, 3);

        SearchRun run = log.find(new SearchRunId(results.searchRun())).orElseThrow();
        // Die ungeprüften Funde der Quelle erscheinen in keiner Zahl.
        assertThat(run.stages()).extracting(SearchRun.StageCount::stage).containsExactly(SearchRun.Stage.QUERIES,
                SearchRun.Stage.CONFIRMED, SearchRun.Stage.DELIVERED);
        assertThat(run.count(SearchRun.Stage.DELIVERED)).hasValue(1);
        assertThat(run.owner()).isEqualTo(user.jobOwner());
    }

    @Test
    void aFailingRunLogNeverFailsTheSearchAndNeverLogsTheQuery() {
        wiki.results("Geheimprojekt Vault", "1");
        SearchRunLog failing = new SearchRunLog() {
            @Override
            public void record(SearchRun run) {
                throw new IllegalStateException("kaputt: " + run.query());
            }

            @Override
            public Optional<SearchRun> find(SearchRunId id) {
                return Optional.empty();
            }

            @Override
            public List<SearchRun> parts(SearchRunId id) {
                return List.of();
            }

            @Override
            public List<SearchRun> recent(String owner, int limit) {
                return List.of();
            }

            @Override
            public int deleteBefore(java.time.Instant before) {
                return 0;
            }
        };

        // System.Logger geht hier an java.util.logging; der Handler sieht jede Meldung samt Parametern.
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(SearchRunLog.class.getName());
        List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logged.add(new java.util.logging.SimpleFormatter().formatMessage(record)
                        + (record.getThrown() == null ? "" : " " + record.getThrown()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        KeywordSearch.Results results;
        try {
            results = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                    KeywordSearch.DEFAULT_LOADS, failing).search(APP, KeywordSearch.Query.of("Geheimprojekt Vault"),
                    null, 10);
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1");
        assertThat(results.searchRun()).isNull();
        assertThat(logged).singleElement().asString().contains("Suchlauf nicht protokolliert")
                .doesNotContain("Geheimprojekt");
    }

    @Test
    void theFindingsOfEachBatchGoToTheCatalogWithoutHoldingUpTheSearch() {
        wiki.results("Hashicorp Vault", "1", "2");
        List<String> offered = Collections.synchronizedList(new ArrayList<>());
        FindingCatalog findings = (finder, sourceId, found) -> found.forEach(finding -> offered.add(
                finder.principal() + ":" + sourceId + ":" + finding.pageId() + ":" + finding.version()));

        KeywordSearch.Results results = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.sourceOrder(), SearchRunLog.NONE, findings)
                .search(APP, KeywordSearch.Query.of("Hashicorp Vault"), null, 50);

        assertThat(offered).containsExactly("app:wiki:1:1", "app:wiki:2:1");
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1", "2");
    }

    @Test
    void withASelectionOnlyItsFindingsGoToTheCatalogSoThatTheFillLoadsNothingOutsideIt() {
        wiki.results("Hashicorp Vault", "1", "2", "3");
        List<String> offered = Collections.synchronizedList(new ArrayList<>());
        FindingCatalog findings = (finder, sourceId, found) -> found.forEach(finding -> offered.add(finding.pageId()));

        new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.sourceOrder(), SearchRunLog.NONE, findings)
                .search(APP, KeywordSearch.Query.of("Hashicorp Vault")
                        .within(PageSet.of(Map.of("wiki", List.of("2")))), null, 50);

        assertThat(offered).containsExactly("2");
    }

    @Test
    void aFailingCatalogNeverFailsTheSearch() {
        wiki.results("Hashicorp Vault", "1");
        FindingCatalog failing = (finder, sourceId, found) -> {
            throw new IllegalStateException("Katalog voll");
        };

        KeywordSearch.Results results = new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off(),
                CandidateSelectionStrategy.sourceOrder(), SearchRunLog.NONE, failing)
                .search(APP, KeywordSearch.Query.of("Hashicorp Vault"), null, 50);

        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1");
    }

    @Test
    void withASelectionOnlyItsPagesAreLoadedAndHitWhileTheCursorKeepsTheOrderOfTheSource() {
        // ADR 0029: Seite samt Unterseiten. „a“ liegt schon im Cache, gehört aber nicht zur Auswahl.
        wiki.results("agil", "a", "b", "c", "d", "e");
        cached.add("a");
        when(texts.search(anyString(), any(), any(), any(PageSet.class), anyInt())).thenAnswer(call -> {
            PageSet pages = call.getArgument(3);
            int limit = call.getArgument(4);
            return new ArrayList<>(cached).stream().filter(id -> pages.contains("wiki", id)).limit(limit)
                    .map(id -> new PageTextIndex.Match("wiki", id, "DEV", "Seite " + id, "Auszug " + id)).toList();
        });
        // Quellweit freigegeben, damit der Beobachter die Funde je Quelle schon während der Suche bekommt.
        AccessGuard sourceWide = new AccessGuard(new com.aresstack.spaceharvester.application.access.AccessPolicy() {
            @Override
            public AccessDecision decide(AccessContext context,
                                         com.aresstack.spaceharvester.application.access.ResourceScope scope,
                                         AccessKind kind) {
                return AccessDecision.ALLOW;
            }

            @Override
            public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
                return AccessDecision.ALLOW;
            }
        });
        KeywordSearch search = new KeywordSearch(catalog, sourceWide, texts, prefetch, 1, ExcerptPriority.off());
        KeywordSearch.Query query = KeywordSearch.Query.of("agil")
                .within(PageSet.of(Map.of("wiki", List.of("b", "d", "e"))));
        List<String> found = Collections.synchronizedList(new ArrayList<>());
        KeywordSearch.Found watcher = (sourceId, hits) -> hits.forEach(hit -> found.add(hit.pageId()));

        List<String> delivered = new ArrayList<>();
        String cursor = null;
        String first = null;
        for (int batch = 0; batch < 10; batch++) {
            KeywordSearch.Results results = search.search(APP, query, cursor, 2, null, watcher);
            results.hits().forEach(hit -> delivered.add(hit.pageId()));
            assertThat(results.found()).extracting(KeywordSearch.Hit::pageId).allMatch(Set.of("b", "d", "e")::contains);
            cursor = results.nextCursor();
            first = first == null ? cursor : first;
            if (cursor == null) {
                break;
            }
        }

        assertThat(delivered).containsExactly("b", "d", "e");
        assertThat(loads).containsExactly(List.of("b"), List.of("d"), List.of("e"));
        assertThat(found).isNotEmpty().allMatch(Set.of("b", "d", "e")::contains);
        String other = first;
        assertThatThrownBy(() -> search.search(APP, KeywordSearch.Query.of("agil"), other, 2))
                .as("Cursor einer anderen Auswahl").isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> search.search(APP, KeywordSearch.Query.of("agil")
                .within(PageSet.of(Map.of("wiki", List.of("b")))), other, 2)).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void anEmptySelectionAsksNoSourceAndFindsNothing() {
        wiki.results("agil", "a");
        cached.add("a");

        KeywordSearch.Results results = search(1, ExcerptPriority.off())
                .search(APP, KeywordSearch.Query.of("agil").within(PageSet.EMPTY), null, 50);

        assertThat(wiki.searches).isEmpty();
        assertThat(loads).isEmpty();
        assertThat(results.hits()).isEmpty();
        assertThat(results.nextCursor()).isNull();
    }

    @Test
    void aWhollyLocalAndCurrentSourceIsNotAskedOnline() {
        // Angelo 27.11.2026: lokal zuerst; online nur, wo Daten nicht lokal oder nicht aktuell vorliegen.
        wiki.results("Wolkenpegel", "1");
        cached.add("7");
        KeywordSearch search = search(1, ExcerptPriority.off());
        search.coverage((source, scope) -> new LocalCoverage.Coverage(true, Set.of("DEV")));

        KeywordSearch.Results results = search.search(APP, KeywordSearch.Query.of("Wolkenpegel"), null, 50);

        assertThat(wiki.searches).isEmpty();
        assertThat(loads).isEmpty();
        assertThat(results.sources()).extracting(KeywordSearch.SourceState::status)
                .containsExactly(KeywordSearch.SourceState.Status.LOCAL);
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("7");
    }

    @Test
    void coveredSpacesAreLeftOutOfTheOnlineSearchAndLocalPagesAreNoProvisionalHits() {
        wiki.results("Wolkenpegel", "1", "2");
        KeywordSearch.Prefetch none = (caller, hits) -> KeywordSearch.Prefetch.Fill.NONE;
        KeywordSearch search = new KeywordSearch(catalog, access, texts, none, 1, ExcerptPriority.off());
        search.coverage((source, scope) -> new LocalCoverage.Coverage(false, Set.of("OPS")));
        // Seite 2 liegt schon im lokalen Index, trifft dort aber nicht: kein Treffer, auch kein vorläufiger.
        when(texts.inputSha256(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "2")))
                .thenReturn(Optional.of("abc"));

        KeywordSearch.Results results = search.search(APP, KeywordSearch.Query.of("Wolkenpegel"), null, 50);

        assertThat(wiki.spacesAsked).isNotEmpty().allSatisfy(spaces -> assertThat(spaces.excluded()).contains("OPS"));
        assertThat(results.found()).extracting(KeywordSearch.Hit::pageId).containsExactly("1");
    }

    private KeywordSearch search(int maxQueries, ExcerptPriority excerpts) {
        return new KeywordSearch(catalog, access, texts, prefetch, maxQueries, excerpts);
    }

    /** Eine Quelle mit eigener Suche: feste Treffer je Anfrage, merkt sich jede Anfrage. */
    private static final class FakeSource implements HarvestSource {

        private final Map<String, List<String>> results = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> searches = Collections.synchronizedList(new ArrayList<>());
        private final List<SearchSpaces> spacesAsked = Collections.synchronizedList(new ArrayList<>());
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
            spacesAsked.add(spaces);
            if (text.equals(failing)) {
                throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                        null);
            }
            return results.getOrDefault(text, List.of()).stream().limit(limit)
                    .map(id -> new SourceSearchMatch(id, "Seite " + id, "Auszug " + id, null, "1", null)).toList();
        }
    }
}
