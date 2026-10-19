package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CandidateSelectionStrategyTest {

    @Test
    void sourceOrderLoadsEveryMissingFindInTheOrderOfTheSources() {
        CandidateSelectionStrategy.Candidates candidates = candidates(List.of("a", "b", "c", "d"),
                List.of("d", "c", "b", "a"), Set.of("b"), 4, 0);

        assertThat(ids(CandidateSelectionStrategy.sourceOrder().select(candidates))).containsExactly("a", "c", "d");
        assertThat(ids(CandidateSelectionStrategy.of(CandidateSelectionStrategy.Kind.ORDER, 2).select(candidates)))
                .containsExactly("a", "c");
    }

    @Test
    void topNTakesTheBestByExcerptAndDoesNotCountWhatIsAlreadyCached() {
        CandidateSelectionStrategy.Candidates candidates = candidates(List.of("a", "b", "c", "d"),
                List.of("d", "c", "b", "a"), Set.of("c"), 4, 0);

        // „c“ liegt aktuell im Cache und belegt keinen der beiden Plätze.
        assertThat(ids(CandidateSelectionStrategy.topN(2).select(candidates))).containsExactly("d", "b");
    }

    @Test
    void adaptiveLoadsASmallBatchCompletely() {
        List<String> found = range(0, 10);

        assertThat(CandidateSelectionStrategy.adaptive().select(candidates(found, found, Set.of(), 4, 0)))
                .hasSize(10);
    }

    @Test
    void adaptiveLoadsWhatTheForegroundManagesInThreeRoundsOfALargeBatch() {
        List<String> found = range(0, 60);

        // drei Runden zu vier Plätzen
        assertThat(ids(CandidateSelectionStrategy.adaptive().select(candidates(found, found, Set.of(), 4, 0))))
                .containsExactlyElementsOf(range(0, 12));
        // mindestens zehn
        assertThat(CandidateSelectionStrategy.adaptive().select(candidates(found, found, Set.of(), 1, 0)))
                .hasSize(10);
        // mehr Plätze, mehr sofort
        assertThat(CandidateSelectionStrategy.adaptive().select(candidates(found, found, Set.of(), 8, 0)))
                .hasSize(24);
    }

    @Test
    void adaptiveHalvesWhenMostFindsAreCachedAndSubtractsTheBacklog() {
        List<String> found = range(0, 40);
        Set<String> cached = Set.copyOf(range(0, 20));

        // Die Hälfte liegt im Cache: zwölf werden sechs, und zwar die besten fehlenden.
        assertThat(ids(CandidateSelectionStrategy.adaptive().select(candidates(found, found, cached, 4, 0))))
                .containsExactlyElementsOf(range(20, 26));
        // Was im Vordergrund schon wartet, geht ab, aber mindestens fünf.
        assertThat(CandidateSelectionStrategy.adaptive().select(candidates(found, found, Set.of(), 4, 4)))
                .hasSize(8);
        assertThat(CandidateSelectionStrategy.adaptive().select(candidates(found, found, Set.of(), 4, 100)))
                .hasSize(5);
    }

    @Test
    void searchLoadsCapsTheAdaptiveSelection() {
        List<String> found = range(0, 60);

        assertThat(CandidateSelectionStrategy.of(CandidateSelectionStrategy.Kind.ADAPTIVE, 3)
                .select(candidates(found, found, Set.of(), 4, 0))).hasSize(3);
    }

    @Test
    void aFailingCacheCheckMeansTheFindLoads() {
        List<KeywordSearch.Hit> hits = hits(List.of("a", "b"));
        CandidateSelectionStrategy.Candidates candidates = new CandidateSelectionStrategy.Candidates(hits, hits, false,
                hit -> {
                    throw new IllegalStateException("Cache nicht lesbar");
                }, null);

        assertThat(ids(CandidateSelectionStrategy.adaptive().select(candidates))).containsExactly("a", "b");
    }

    @Test
    void namesAreReadLeniently() {
        assertThat(CandidateSelectionStrategy.Kind.of(" Top-N ")).isEqualTo(CandidateSelectionStrategy.Kind.TOP_N);
        assertThat(CandidateSelectionStrategy.Kind.of("adaptive")).isEqualTo(CandidateSelectionStrategy.Kind.ADAPTIVE);
        assertThat(CandidateSelectionStrategy.Kind.of("ORDER")).isEqualTo(CandidateSelectionStrategy.Kind.ORDER);
        assertThatThrownBy(() -> CandidateSelectionStrategy.Kind.of("alle"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CandidateSelectionStrategy.topN(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static CandidateSelectionStrategy.Candidates candidates(List<String> found, List<String> ranked,
                                                                    Set<String> cached, int parallelism,
                                                                    int backlog) {
        return new CandidateSelectionStrategy.Candidates(hits(found), hits(ranked), true,
                hit -> cached.contains(hit.pageId()), new CandidateSelectionStrategy.Capacity(parallelism, backlog));
    }

    private static List<String> range(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "p" + i).toList();
    }

    private static List<KeywordSearch.Hit> hits(List<String> ids) {
        return ids.stream().map(id -> new KeywordSearch.Hit("wiki", id, null, "T " + id, "", null, "1", null, null))
                .toList();
    }

    private static List<String> ids(List<KeywordSearch.Hit> hits) {
        return hits.stream().map(KeywordSearch.Hit::pageId).toList();
    }
}
