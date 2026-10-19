package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Completion;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.RankedHit;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Status;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Variant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchOrchestratorTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final String ORIGINAL = "Verlängerung Dienstreise beantragen";

    private final FakeSearch search = new FakeSearch();
    private SearchOrchestrator orchestrator;

    @AfterEach
    void close() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void theOriginalSearchesFirstThenVariantsRunInWavesAndAreReleasedInPriorityOrder() throws Exception {
        CountDownLatch slowFirst = new CountDownLatch(1);
        search.results(ORIGINAL, found("o"));
        search.results("v1", found("a")).blocking("v1", slowFirst);
        search.results("v2", found("b"));
        search.results("v3", found("c"));
        search.results("v4", found("d"));
        Recorder events = new Recorder();
        ProgressiveSearch running = orchestrator(List.of("v1", "v2", "v3", "v4"), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile(SearchProfile.Mode.ALL_VARIANTS, 2), events);

        awaitCalls(3);
        // v2 ist fertig, wird aber erst nach v1 freigegeben; die zweite Welle wartet auf die erste.
        assertThat(search.calls()).containsExactlyInAnyOrder(ORIGINAL, "v1", "v2");
        assertThat(events.lastRanked()).extracting(hit -> hit.hit().pageId()).containsExactly("o");
        slowFirst.countDown();
        ProgressiveResult result = running.result().get(10, TimeUnit.SECONDS);

        assertThat(result.reason()).isEqualTo(Completion.ALL_VARIANTS_DONE);
        assertThat(result.hits()).extracting(hit -> hit.hit().pageId()).containsExactly("o", "a", "b", "c", "d");
        assertThat(result.variants()).extracting(Variant::status).containsOnly(Status.DONE);
        assertThat(result.variants()).extracting(variant -> variant.query().priority()).containsExactly(0, 1, 2, 3, 4);
        assertThat(search.calls().subList(3, 5)).containsExactlyInAnyOrder("v3", "v4");
        assertThat(events.completed).isNotNull();
    }

    @Test
    void aTimedOutVariantReleasesTheNextAndItsLateHitsAreStillSortedIn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        search.results(ORIGINAL, found("o"));
        search.results("v1", found("late")).blocking("v1", release);
        search.results("v2", found("b"));
        search.results("v3", found("c")).onCall("v3", release::countDown).delay("v3", 100);
        SearchProfile profile = profile(SearchProfile.Mode.ALL_VARIANTS, 2).withExecution(
                new SearchExecutionPolicy(2, 2, Duration.ofMillis(300), 0));

        ProgressiveResult result = orchestrator(List.of("v1", "v2", "v3"), HitScorer.none())
                .searchAsync(APP, KeywordSearch.Query.of(ORIGINAL), profile).result().get(10, TimeUnit.SECONDS);

        assertThat(result.hits()).extracting(hit -> hit.hit().pageId()).containsExactly("o", "b", "late", "c");
        assertThat(result.variants()).extracting(Variant::status).containsOnly(Status.DONE);
        assertThat(result.reason()).isEqualTo(Completion.ALL_VARIANTS_DONE);
    }

    @Test
    void reachingTheQualityGoalEndsTheExpansionAndSkipsTheRest() throws Exception {
        search.results(ORIGINAL, found("a", "b"));
        HitScorer good = (original, hits) -> hits.stream().mapToDouble(hit -> 0.9).toArray();

        ProgressiveResult result = orchestrator(List.of("v1", "v2"), good).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), SearchProfile.defaults().withQuality(2, 0.5))
                .result().get(10, TimeUnit.SECONDS);

        assertThat(result.reason()).isEqualTo(Completion.QUALITY_REACHED);
        assertThat(search.calls()).containsExactly(ORIGINAL);
        // Die Varianten stehen als übersprungen da, wenn das Modell vor dem Ende geantwortet hat.
        assertThat(result.variants().get(0).status()).isEqualTo(Status.DONE);
        assertThat(result.variants().subList(1, result.variants().size())).extracting(Variant::status)
                .allMatch(status -> status == Status.SKIPPED);
    }

    @Test
    void hitsAreRankedAgainstTheOriginalQueryNeverAgainstTheVariant() throws Exception {
        search.results(ORIGINAL, found("weak"));
        search.results("v1", found("strong"));
        List<String> scoredAgainst = Collections.synchronizedList(new ArrayList<>());
        HitScorer scorer = (original, hits) -> {
            scoredAgainst.add(original);
            return hits.stream().mapToDouble(hit -> hit.pageId().equals("strong") ? 0.9 : 0.1).toArray();
        };

        ProgressiveResult result = orchestrator(List.of("v1"), scorer).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile(SearchProfile.Mode.ALL_VARIANTS, 3))
                .result().get(10, TimeUnit.SECONDS);

        assertThat(scoredAgainst).containsOnly(ORIGINAL);
        assertThat(result.hits()).extracting(hit -> hit.hit().pageId()).containsExactly("strong", "weak");
        assertThat(result.hits()).extracting(RankedHit::foundBy).containsExactly(1, 0);
    }

    @Test
    void aFindConfirmedByTheCacheKeepsItsIdentityInsteadOfAppearingTwice() throws Exception {
        search.results(ORIGINAL, found("a"));
        search.put("v1", new KeywordSearch.Results("wiki", List.of(hit("a")), List.of(), null, true, 0,
                List.of(hit("b")), null));

        ProgressiveResult result = orchestrator(List.of("v1"), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile(SearchProfile.Mode.ALL_VARIANTS, 3))
                .result().get(10, TimeUnit.SECONDS);

        assertThat(result.hits()).extracting(hit -> hit.hit().pageId(), RankedHit::confirmed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", true),
                        org.assertj.core.groups.Tuple.tuple("b", false));
    }

    @Test
    void theSearchBudgetEndsTheSearchWithoutClaimingQuality() throws Exception {
        search.results(ORIGINAL, found("o"));
        search.results("v1", found("a"));
        HitScorer poor = (original, hits) -> hits.stream().mapToDouble(hit -> 0.1).toArray();

        ProgressiveResult result = orchestrator(List.of("v1", "v2", "v3"), poor).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), SearchProfile.defaults().withBudget(Duration.ofSeconds(30), 2))
                .result().get(10, TimeUnit.SECONDS);

        assertThat(result.reason()).isEqualTo(Completion.BUDGET_EXHAUSTED);
        assertThat(search.calls()).containsExactly(ORIGINAL, "v1");
        assertThat(result.variants()).extracting(Variant::status)
                .containsExactly(Status.DONE, Status.DONE, Status.SKIPPED, Status.SKIPPED);
    }

    @Test
    void theTimeBudgetEndsTheSearchAsExhaustedNeverAsQualityReached() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        search.results(ORIGINAL, found("o")).blocking(ORIGINAL, never);

        ProgressiveResult result = orchestrator(List.of("v1"), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), SearchProfile.defaults().withBudget(Duration.ofMillis(200), 12))
                .result().get(10, TimeUnit.SECONDS);
        never.countDown();

        assertThat(result.reason()).isEqualTo(Completion.BUDGET_EXHAUSTED);
        assertThat(result.hits()).isEmpty();
        assertThat(result.variants()).extracting(Variant::status).containsExactly(Status.FAILED, Status.SKIPPED);
    }

    @Test
    void withoutAnySuccessfulSearchTheSearchFails() throws Exception {
        search.failing(ORIGINAL, 5);

        ProgressiveResult result = orchestrator(List.of(), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), SearchProfile.defaults()).result().get(10, TimeUnit.SECONDS);

        assertThat(result.reason()).isEqualTo(Completion.FAILED);
    }

    @Test
    void cancellingKeepsWhatWasFoundAndStopsTheLoading() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        search.put(ORIGINAL, new KeywordSearch.Results("wiki", List.of(), List.of(), null, false, 1,
                List.of(hit("o")), new KeywordSearch.Progress("load-1", 1, 0, 0)));
        search.results("v1", found("a")).blocking("v1", never);
        ProgressiveSearch running = orchestrator(List.of("v1"), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile(SearchProfile.Mode.ALL_VARIANTS, 3));

        awaitCalls(2);
        assertThat(running.cancel()).isTrue();
        ProgressiveResult result = running.result().get(10, TimeUnit.SECONDS);
        never.countDown();

        assertThat(result.reason()).isEqualTo(Completion.CANCELLED);
        assertThat(result.hits()).extracting(hit -> hit.hit().pageId()).containsExactly("o");
        assertThat(search.cancelledLoads).containsExactly("load-1");
    }

    @Test
    void aTransientFailureIsRetriedOnceAndAllFailuresEndAsFailed() throws Exception {
        search.failing(ORIGINAL, 1).results(ORIGINAL, found("o"));

        ProgressiveResult retried = orchestrator(List.of(), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), SearchProfile.defaults()).result().get(10, TimeUnit.SECONDS);
        assertThat(retried.reason()).isEqualTo(Completion.ALL_VARIANTS_DONE);
        assertThat(retried.hits()).hasSize(1);

        search.failing(ORIGINAL, 5);
        ProgressiveResult failed = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                SearchProfile.defaults()).result().get(10, TimeUnit.SECONDS);
        assertThat(failed.reason()).isEqualTo(Completion.FAILED);
    }

    @Test
    void duplicateAndOriginalVariantsAreDroppedAndPrioritiesFollowTheOrder() throws Exception {
        search.results(ORIGINAL, found("o"));
        List<List<Variant>> seen = Collections.synchronizedList(new ArrayList<>());

        ProgressiveResult result = orchestrator(List.of(ORIGINAL.toUpperCase(), "v1", " v1 ", "", "v2"),
                HitScorer.none()).searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                profile(SearchProfile.Mode.ALL_VARIANTS, 3), new ProgressiveSearchListener() {
                    @Override
                    public void variants(List<Variant> variants) {
                        seen.add(variants);
                    }
                }).result().get(10, TimeUnit.SECONDS);

        assertThat(result.variants()).extracting(variant -> variant.query().text(), variant -> variant.query().priority())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ORIGINAL, 0),
                        org.assertj.core.groups.Tuple.tuple("v1", 1), org.assertj.core.groups.Tuple.tuple("v2", 2));
        assertThat(seen).isNotEmpty();
    }

    @Test
    void theProfileChoosesBetweenModelAndRulesAndTheOriginalStaysFirst() throws Exception {
        search.results(ORIGINAL, found("o"));
        search.results("modell", found("m"));
        search.results("regel", found("r"));
        QueryExpansionStrategy rules = (original, max) -> List.of("regel");
        // Das Modell wiederholt die ursprüngliche Anfrage: Sie bleibt trotzdem einmal und zuerst.
        AiQueryExpansion model = new AiQueryExpansion(() -> java.util.Optional.of((instructions, question) ->
                "{\"queries\":[{\"text\":\"" + question + "\",\"priority\":1},{\"text\":\"modell\",\"priority\":2}]}"),
                rules, Duration.ofSeconds(5));
        orchestrator = new SearchOrchestrator(search, model, rules, HitScorer.none());
        SearchProfile all = profile(SearchProfile.Mode.ALL_VARIANTS, 2);

        ProgressiveResult byModel = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL), all).result()
                .get(10, TimeUnit.SECONDS);
        ProgressiveResult byRules = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                all.withExpansion(SearchProfile.Expansion.RULES)).result().get(10, TimeUnit.SECONDS);

        assertThat(SearchProfile.defaults().expansion()).isEqualTo(SearchProfile.Expansion.AI);
        assertThat(byModel.variants()).extracting(variant -> variant.query().text()).containsExactly(ORIGINAL,
                "modell");
        assertThat(byRules.variants()).extracting(variant -> variant.query().text()).containsExactly(ORIGINAL,
                "regel");
    }

    @Test
    void anOriginalQueryReachingTheGoalDoesNotWaitForTheModel() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        search.results(ORIGINAL, found("a", "b"));
        QueryExpansionStrategy slow = (original, max) -> {
            try {
                never.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of("v1");
        };
        HitScorer good = (original, hits) -> hits.stream().mapToDouble(hit -> 0.9).toArray();
        orchestrator = new SearchOrchestrator(search, slow, good);

        ProgressiveResult result = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                SearchProfile.defaults().withQuality(2, 0.5)).result().get(3, TimeUnit.SECONDS);
        never.countDown();

        assertThat(result.reason()).isEqualTo(Completion.QUALITY_REACHED);
        assertThat(result.variants()).extracting(variant -> variant.query().text()).containsExactly(ORIGINAL);
    }

    @Test
    void aSearchReturningAfterTheCancelStillHasItsLoadingCancelled() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        search.results(ORIGINAL, found("o"));
        search.put("v1", new KeywordSearch.Results("wiki", List.of(), List.of(), null, false, 1, List.of(hit("a")),
                new KeywordSearch.Progress("load-v1", 1, 0, 0)));
        search.blocking("v1", release);
        ProgressiveSearch running = orchestrator(List.of("v1"), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile(SearchProfile.Mode.ALL_VARIANTS, 3));

        awaitCalls(2);
        running.cancel();
        assertThat(running.result().get(10, TimeUnit.SECONDS).reason()).isEqualTo(Completion.CANCELLED);
        release.countDown();

        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!search.cancelledLoads.contains("load-v1") && System.nanoTime() < until) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(search.cancelledLoads).contains("load-v1");
    }

    @Test
    void lateHitsOfTheLastVariantAreWaitedForWithinTheTimeBudget() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        search.results(ORIGINAL, found("o"));
        search.results("v1", found("late")).blocking("v1", release);
        SearchProfile profile = profile(SearchProfile.Mode.ALL_VARIANTS, 1).withExecution(
                new SearchExecutionPolicy(1, 1, Duration.ofMillis(200), 0));
        ProgressiveSearch running = orchestrator(List.of("v1"), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile);

        TimeUnit.MILLISECONDS.sleep(500);
        release.countDown();
        ProgressiveResult result = running.result().get(10, TimeUnit.SECONDS);

        assertThat(result.hits()).extracting(hit -> hit.hit().pageId()).containsExactly("o", "late");
        assertThat(result.variants()).extracting(Variant::status).containsOnly(Status.DONE);
        assertThat(result.reason()).isEqualTo(Completion.ALL_VARIANTS_DONE);
    }

    @Test
    void anExhaustedTimeBudgetWinsOverTheQualityGoal() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        search.results(ORIGINAL, found("o"));
        search.results("v1", found("x")).blocking("v1", never);
        search.results("v2", found("b"));
        HitScorer good = (original, hits) -> hits.stream().mapToDouble(hit -> 0.9).toArray();
        SearchProfile profile = SearchProfile.defaults().withQuality(2, 0.5).withBudget(Duration.ofMillis(300), 12)
                .withExecution(new SearchExecutionPolicy(2, 2, Duration.ofSeconds(5), 0));

        ProgressiveResult result = orchestrator(List.of("v1", "v2", "v3"), good).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile).result().get(10, TimeUnit.SECONDS);
        never.countDown();

        assertThat(result.reason()).isEqualTo(Completion.BUDGET_EXHAUSTED);
    }

    @Test
    void aFindConfirmedByTheCacheIsScoredAgainWithItsNewExcerpt() throws Exception {
        search.results(ORIGINAL, found("a", "b"));
        search.put("v1", new KeywordSearch.Results("wiki", List.of(new KeywordSearch.Hit("wiki", "a", "DEV",
                "Seite a", "Inhalt a", null, "1", null, null)), List.of(), null, true, 0, List.of(), null));
        HitScorer scorer = (original, hits) -> hits.stream()
                .mapToDouble(hit -> hit.snippet().startsWith("Inhalt") ? 0.9 : hit.pageId().equals("b") ? 0.5 : 0.1)
                .toArray();

        ProgressiveResult result = orchestrator(List.of("v1"), scorer).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), profile(SearchProfile.Mode.ALL_VARIANTS, 3))
                .result().get(10, TimeUnit.SECONDS);

        assertThat(result.hits()).extracting(hit -> hit.hit().pageId(), RankedHit::score)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", 0.9),
                        org.assertj.core.groups.Tuple.tuple("b", 0.5));
    }

    @Test
    void closingCancelsRunningSearchesAndWaitsForTheirQueries() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        search.results(ORIGINAL, found("o")).blocking(ORIGINAL, release);
        ProgressiveSearch running = orchestrator(List.of(), HitScorer.none()).searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), SearchProfile.defaults());
        awaitCalls(1);
        Thread releaser = Thread.ofVirtual().start(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });

        orchestrator.close();

        assertThat(search.returned).hasValue(1);
        assertThat(running.result()).isDone();
        assertThat(running.result().join().reason()).isEqualTo(Completion.CANCELLED);
        releaser.join();
    }

    @Test
    void semanticallyEveryVariantRunsThroughTheSemanticSearchAgainstTheOriginalAndKeepsItsSimilarity()
            throws Exception {
        List<String> runs = Collections.synchronizedList(new ArrayList<>());
        VariantSearch semantic = new VariantSearch() {
            @Override
            public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
                throw new AssertionError("ohne ursprüngliche Anfrage aufgerufen");
            }

            @Override
            public Outcome run(AccessContext caller, KeywordSearch.Query variant, String original, int limit) {
                runs.add(variant.text() + " | " + original);
                // Die Vektorsuche mit der ursprünglichen Anfrage; v1 hat eine weitere Seite in den Cache geladen.
                List<KeywordSearch.Hit> hits = variant.text().equals("v1") ? List.of(hit("a"), hit("b"))
                        : List.of(hit("a"));
                return new Outcome(new KeywordSearch.Results("wiki", hits, List.of(), null, true, 0),
                        variant.text().equals("v1") ? Map.of(key("a"), 0.4, key("b"), 0.8) : Map.of(key("a"), 0.4));
            }
        };
        HitScorer crossEncoder = (original, hits) -> {
            throw new AssertionError("semantisch bewertet die Ähnlichkeit, nicht der Cross-Encoder");
        };
        orchestrator = new SearchOrchestrator(search, semantic, (original, max) -> List.of("v1", "v2"),
                (original, max) -> List.of(), crossEncoder);

        ProgressiveResult result = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                profile(SearchProfile.Mode.ALL_VARIANTS, 3).withRanking(SearchProfile.Ranking.SEMANTIC))
                .result().get(10, TimeUnit.SECONDS);

        assertThat(search.calls()).isEmpty();
        assertThat(runs).containsExactlyInAnyOrder(ORIGINAL + " | " + ORIGINAL, "v1 | " + ORIGINAL,
                "v2 | " + ORIGINAL);
        assertThat(result.reason()).isEqualTo(Completion.ALL_VARIANTS_DONE);
        assertThat(result.hits()).extracting(hit -> hit.hit().pageId(), RankedHit::score, RankedHit::foundBy,
                        RankedHit::confirmed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("b", 0.8, 1, true),
                        org.assertj.core.groups.Tuple.tuple("a", 0.4, 0, true));
        assertThat(result.variants()).extracting(Variant::newHits).containsExactly(1, 1, 0);
    }

    @Test
    void theSimilarityReachesTheQualityGoalOfASemanticSearch() throws Exception {
        VariantSearch semantic = new VariantSearch() {
            @Override
            public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
                throw new AssertionError("ohne ursprüngliche Anfrage aufgerufen");
            }

            @Override
            public Outcome run(AccessContext caller, KeywordSearch.Query variant, String original, int limit) {
                return new Outcome(new KeywordSearch.Results("wiki", List.of(hit("a"), hit("b")), List.of(), null,
                        true, 0), Map.of(key("a"), 0.7, key("b"), 0.6));
            }
        };
        orchestrator = new SearchOrchestrator(search, semantic, (original, max) -> List.of("v1"),
                (original, max) -> List.of(), HitScorer.none());

        ProgressiveResult result = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                SearchProfile.defaults().withQuality(2, 0.5).withRanking(SearchProfile.Ranking.SEMANTIC))
                .result().get(10, TimeUnit.SECONDS);

        assertThat(result.reason()).isEqualTo(Completion.QUALITY_REACHED);
        assertThat(result.hits()).extracting(hit -> hit.hit().pageId()).containsExactly("a", "b");
    }

    @Test
    void aSemanticSearchThatIsNotPossibleIsRefusedBeforeAnythingIsSearched() {
        SearchProfile semanticProfile = SearchProfile.defaults().withRanking(SearchProfile.Ranking.SEMANTIC);
        SearchOrchestrator withoutSemantic = orchestrator(List.of("v1"), HitScorer.none());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withoutSemantic.searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), semanticProfile)).isInstanceOf(IllegalArgumentException.class);
        withoutSemantic.close();

        VariantSearch unavailable = new VariantSearch() {
            @Override
            public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
                throw new AssertionError("nie gesucht");
            }

            @Override
            public void requireAvailable(AccessContext caller, KeywordSearch.Query query) {
                throw new IllegalStateException("Kein Embedding-Dienst eingerichtet");
            }
        };
        orchestrator = new SearchOrchestrator(search, unavailable, (original, max) -> List.of("v1"),
                (original, max) -> List.of(), HitScorer.none());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.searchAsync(APP,
                KeywordSearch.Query.of(ORIGINAL), semanticProfile)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(ORIGINAL);
        assertThat(search.calls()).isEmpty();
    }

    @Test
    void theKeywordProfileKeepsTheKeywordSearchEvenWithASemanticSearchWired() throws Exception {
        search.results(ORIGINAL, found("o"));
        VariantSearch semantic = (caller, query, limit) -> {
            throw new AssertionError("nur mit Haken");
        };
        orchestrator = new SearchOrchestrator(search, semantic, (original, max) -> List.of(),
                (original, max) -> List.of(), HitScorer.none());

        ProgressiveResult result = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                SearchProfile.defaults()).result().get(10, TimeUnit.SECONDS);

        assertThat(result.hits()).extracting(hit -> hit.hit().pageId()).containsExactly("o");
        assertThat(search.calls()).containsExactly(ORIGINAL);
    }

    private static com.aresstack.spaceharvester.application.snapshot.PageKey key(String pageId) {
        return new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", pageId);
    }

    private SearchOrchestrator orchestrator(List<String> variants, HitScorer scorer) {
        orchestrator = new SearchOrchestrator(search, (original, max) -> variants, scorer);
        return orchestrator;
    }

    private static SearchProfile profile(SearchProfile.Mode mode, int waveSize) {
        return SearchProfile.defaults().withMode(mode).withExecution(new SearchExecutionPolicy(waveSize, waveSize,
                Duration.ofSeconds(5), 0));
    }

    private void awaitCalls(int count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (search.calls().size() < count && System.nanoTime() < until) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        // Gibt der Freigabe der fertigen Anfragen Zeit.
        TimeUnit.MILLISECONDS.sleep(100);
    }

    private static KeywordSearch.Results found(String... pageIds) {
        return new KeywordSearch.Results("wiki", List.of(), List.of(), null, true, 0,
                java.util.Arrays.stream(pageIds).map(SearchOrchestratorTest::hit).toList(), null);
    }

    private static KeywordSearch.Hit hit(String pageId) {
        return new KeywordSearch.Hit("wiki", pageId, null, "Seite " + pageId, "Auszug " + pageId, null, "1", null,
                null);
    }

    /** Feste Ergebnisse je Suchtext; kann blockieren, verzögern und scheitern. */
    private static final class FakeSearch implements VariantSearch {

        private final Map<String, KeywordSearch.Results> results = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> blocking = new ConcurrentHashMap<>();
        private final Map<String, Long> delays = new ConcurrentHashMap<>();
        private final Map<String, Runnable> onCall = new ConcurrentHashMap<>();
        private final Map<String, Integer> failures = new ConcurrentHashMap<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<String> cancelledLoads = Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicInteger returned = new java.util.concurrent.atomic.AtomicInteger();

        FakeSearch results(String text, KeywordSearch.Results result) {
            results.put(text, result);
            return this;
        }

        void put(String text, KeywordSearch.Results result) {
            results.put(text, result);
        }

        FakeSearch blocking(String text, CountDownLatch latch) {
            blocking.put(text, latch);
            return this;
        }

        FakeSearch delay(String text, long millis) {
            delays.put(text, millis);
            return this;
        }

        FakeSearch onCall(String text, Runnable action) {
            onCall.put(text, action);
            return this;
        }

        FakeSearch failing(String text, int times) {
            failures.put(text, times);
            return this;
        }

        List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        @Override
        public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
            String text = query.text();
            calls.add(text);
            Runnable action = onCall.get(text);
            if (action != null) {
                action.run();
            }
            int remaining = failures.getOrDefault(text, 0);
            if (remaining > 0) {
                failures.put(text, remaining - 1);
                throw new IllegalStateException("Quelle antwortet nicht");
            }
            try {
                CountDownLatch latch = blocking.get(text);
                if (latch != null) {
                    latch.await(10, TimeUnit.SECONDS);
                }
                Long delay = delays.get(text);
                if (delay != null) {
                    TimeUnit.MILLISECONDS.sleep(delay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            returned.incrementAndGet();
            return results.getOrDefault(text, found());
        }

        @Override
        public void cancelLoading(String progressId) {
            cancelledLoads.add(progressId);
        }
    }

    private static final class Recorder implements ProgressiveSearchListener {

        private final List<List<RankedHit>> ranked = Collections.synchronizedList(new ArrayList<>());
        volatile ProgressiveResult completed;

        @Override
        public void ranked(List<RankedHit> hits) {
            ranked.add(hits);
        }

        @Override
        public void completed(ProgressiveResult result) {
            completed = result;
        }

        List<RankedHit> lastRanked() {
            synchronized (ranked) {
                return ranked.isEmpty() ? List.of() : ranked.getLast();
            }
        }
    }
}
