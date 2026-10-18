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
        assertThat(result.variants()).extracting(Variant::status)
                .containsExactly(Status.DONE, Status.SKIPPED, Status.SKIPPED);
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
