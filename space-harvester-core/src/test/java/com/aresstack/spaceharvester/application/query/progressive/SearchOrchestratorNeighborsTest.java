package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.RankedHit;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Suchmethode „Nachbarseiten“ (R2) in der progressiven Suche: aus ändert nichts, an kommen nur Nachbarn, die die
 * ursprüngliche Anfrage in derselben Suche treffen, mit der Ursache {@code NEIGHBOR}.
 */
class SearchOrchestratorNeighborsTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final String ORIGINAL = "Nachtlauf Abschluss";
    private static final HitReason.Neighbor VIA_CHILD = new HitReason.Neighbor("wiki", "o", "Seite o",
            HitReason.Neighbor.Edge.CHILD);
    private static final HitReason.Neighbor VIA_LINK = new HitReason.Neighbor("wiki", "o", "Seite o",
            HitReason.Neighbor.Edge.LINKS_TO);

    private final List<KeywordSearch.Query> queries = Collections.synchronizedList(new ArrayList<>());
    private final List<List<String>> seedsSeen = Collections.synchronizedList(new ArrayList<>());
    private SearchOrchestrator orchestrator;

    @AfterEach
    void close() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    /**
     * Die Suche: ohne Seitengrenze trifft die Anfrage o und a, in der Grenze nur Seiten, die „matching“ trifft
     * (n1 trifft, n2 nicht), außerdem eine Seite außerhalb der Kandidaten, die nie übernommen werden darf.
     */
    private final VariantSearch search = new VariantSearch() {
        @Override
        public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
            queries.add(query);
            if (query.pages() == null || query.pages().contains(new PageKey("wiki", "o"))) {
                return confirmed(query.pages(), "o", "a");
            }
            return confirmed(query.pages(), "n1", "fremd");
        }
    };

    private final NeighborSource neighbors = (caller, seeds) -> {
        seedsSeen.add(seeds.stream().map(KeywordSearch.Hit::pageId).toList());
        Map<PageKey, HitReason.Neighbor> found = new LinkedHashMap<>();
        found.put(new PageKey("wiki", "a"), VIA_LINK);
        found.put(new PageKey("wiki", "n1"), VIA_CHILD);
        found.put(new PageKey("wiki", "n2"), VIA_LINK);
        return found;
    };

    private static KeywordSearch.Results confirmed(PageSet within, String... pageIds) {
        List<KeywordSearch.Hit> hits = java.util.Arrays.stream(pageIds)
                .filter(id -> within == null || within.contains("wiki", id) || id.equals("fremd"))
                .map(id -> new KeywordSearch.Hit("wiki", id, null, "Seite " + id, "Auszug " + id, null, "1", null,
                        null).withReasons(List.of(HitReason.text(ORIGINAL, null, List.of(), List.of("nachtlauf")))))
                .toList();
        return new KeywordSearch.Results("wiki", hits, List.of(), null, true, 0, List.of(), null);
    }

    private ProgressiveResult run(NeighborSource source, SearchProfile profile, KeywordSearch.Query query)
            throws Exception {
        orchestrator = new SearchOrchestrator(search, null, (original, max) -> List.of(),
                (original, max) -> List.of(), HitScorer.none(), com.aresstack.spaceharvester.application.searchrun
                .SearchRunLog.NONE, source);
        try {
            return orchestrator.searchAsync(APP, query, profile).result().get(10, TimeUnit.SECONDS);
        } finally {
            orchestrator.close();
            orchestrator = null;
        }
    }

    private static SearchProfile profile() {
        return SearchProfile.defaults().withMode(SearchProfile.Mode.ALL_VARIANTS).withExecution(
                new SearchExecutionPolicy(2, 2, Duration.ofSeconds(5), 0));
    }

    private static List<String> ids(ProgressiveResult result) {
        return result.hits().stream().map(hit -> hit.hit().pageId()).toList();
    }

    @Test
    void withoutTheMethodTheResultIsTheSameAsWithoutNeighborsAndNoFurtherSearchRuns() throws Exception {
        ProgressiveResult plain = run(NeighborSource.NONE, profile(), KeywordSearch.Query.of(ORIGINAL));
        queries.clear();

        ProgressiveResult off = run(neighbors, profile(), KeywordSearch.Query.of(ORIGINAL));

        assertThat(ids(off)).isEqualTo(ids(plain)).containsExactly("o", "a");
        assertThat(off.hits()).extracting(hit -> hit.hit().reasons()).isEqualTo(plain.hits().stream()
                .map(hit -> hit.hit().reasons()).toList());
        assertThat(queries).hasSize(1);
        assertThat(seedsSeen).isEmpty();
    }

    @Test
    void withTheMethodOnlyNeighborsThatMatchTheQueryComeWithReasonAndVia() throws Exception {
        ProgressiveResult result = run(neighbors, profile().withNeighbors(true), KeywordSearch.Query.of(ORIGINAL));

        assertThat(ids(result)).containsExactly("o", "a", "n1");
        RankedHit neighbor = result.hits().get(2);
        assertThat(neighbor.hit().reasons()).extracting(HitReason::kind)
                .containsExactly(HitReason.Kind.TEXT, HitReason.Kind.NEIGHBOR);
        assertThat(neighbor.hit().reasons().get(1).via()).isEqualTo(VIA_CHILD);
        // Schon gefundene Seiten sind keine Kandidaten und bekommen keine Nachbar-Ursache.
        assertThat(result.hits().get(1).hit().reasons()).extracting(HitReason::kind)
                .containsExactly(HitReason.Kind.TEXT);
        assertThat(seedsSeen).containsExactly(List.of("o", "a"));
        assertThat(queries).hasSize(2);
        KeywordSearch.Query within = queries.get(1);
        assertThat(within.text()).isEqualTo(ORIGINAL);
        assertThat(within.pages().asMap()).isEqualTo(Map.of("wiki", Set.of("n1", "n2")));
    }

    @Test
    void neighborsOutsideTheSelectionOfTheSearchAreNeverCandidates() throws Exception {
        PageSet selection = PageSet.of(Map.of("wiki", List.of("o", "a", "n2")));

        ProgressiveResult result = run(neighbors, profile().withNeighbors(true),
                KeywordSearch.Query.of(ORIGINAL).within(selection));

        assertThat(ids(result)).containsExactly("o", "a");
        assertThat(queries.get(1).pages().asMap()).isEqualTo(Map.of("wiki", Set.of("n2")));
    }

    @Test
    void withoutNeighborsTheMethodRunsNoFurtherSearch() throws Exception {
        ProgressiveResult result = run((caller, seeds) -> Map.of(), profile().withNeighbors(true),
                KeywordSearch.Query.of(ORIGINAL));

        assertThat(ids(result)).containsExactly("o", "a");
        assertThat(queries).hasSize(1);
    }

    @Test
    void anExhaustedSearchBudgetStartsNoNeighborSearch() throws Exception {
        ProgressiveResult result = run(neighbors, profile().withNeighbors(true).withBudget(Duration.ofSeconds(30), 1),
                KeywordSearch.Query.of(ORIGINAL));

        assertThat(ids(result)).containsExactly("o", "a");
        assertThat(queries).hasSize(1);
        assertThat(seedsSeen).isEmpty();
    }

    @Test
    void slowCollectingOfNeighborsDoesNotHoldTheSearchBeyondItsDeadline() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        NeighborSource slow = (caller, seeds) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return neighbors.neighbors(caller, seeds);
        };
        orchestrator = new SearchOrchestrator(search, null, (original, max) -> List.of(),
                (original, max) -> List.of(), HitScorer.none(), com.aresstack.spaceharvester.application.searchrun
                .SearchRunLog.NONE, slow);
        long start = System.nanoTime();

        ProgressiveResult result = orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL),
                profile().withNeighbors(true).withBudget(Duration.ofMillis(800), 12)).result().get(5, TimeUnit.SECONDS);
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        release.countDown();

        assertThat(took).isLessThan(Duration.ofSeconds(4));
        assertThat(ids(result)).containsExactly("o", "a");
        assertThat(queries).hasSize(1);
    }
}
