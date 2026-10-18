package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wie {@code ExcerptPriorityTest} im Enterprise AI Client (#154), dazu die Freigabe für externe Verarbeitung. */
class ExcerptPriorityTest {

    private final List<List<String>> scored = new ArrayList<>();
    private final List<KeywordSearch.Hit> hits = List.of(
            hit("wiki", "a", "Kantine", "Speiseplan der Kantine und Urlaub"),
            hit("wiki", "b", "Urlaubsantrag", "So stellt man einen Urlaubsantrag"),
            hit("wiki", "c", "Reisekosten", "Reisekosten nach dem Urlaub abrechnen"));

    @Test
    void higherScoredHitsLoadFirstAndNothingIsDropped() {
        ExcerptPriority.Priority priority = scoring(0.1, 0.9, 0.5).prioritize("Urlaub", hits);

        assertThat(scored).as("alle Auszüge zusammen bewertet, Titel und Auszug").hasSize(1);
        assertThat(scored.getFirst()).hasSize(3);
        assertThat(scored.getFirst().get(1)).isEqualTo("Urlaubsantrag\nSo stellt man einen Urlaubsantrag");
        assertThat(priority.ranked()).isTrue();
        assertThat(priority.order()).containsExactly(1, 2, 0);
        assertThat(priority.apply(hits)).extracting(KeywordSearch.Hit::pageId).containsExactly("b", "c", "a");
    }

    @Test
    void aFailingRerankerFallsBackToTheSourceOrder() {
        ExcerptPriority failing = new ExcerptPriority((query, documents) -> {
            throw new IllegalStateException("down");
        }, sourceId -> true);

        ExcerptPriority.Priority priority = failing.prioritize("Urlaub", hits);

        assertThat(priority.ranked()).isFalse();
        assertThat(priority.fallback()).contains("IllegalStateException");
        assertThat(priority.order()).containsExactly(0, 1, 2);
    }

    @Test
    void wrongOrInvalidScoresFallBackAndWithoutRerankerNothingIsCalled() {
        assertThat(scoring(0.5).prioritize("Urlaub", hits).ranked()).isFalse();
        assertThat(scoring(0.5, Double.NaN, 0.1).prioritize("Urlaub", hits).order()).containsExactly(0, 1, 2);
        assertThat(scoring(0.5, Double.POSITIVE_INFINITY, 0.1).prioritize("Urlaub", hits).ranked()).isFalse();
        assertThat(scored).hasSize(3);

        ExcerptPriority.Priority off = ExcerptPriority.off().prioritize("Urlaub", hits);
        assertThat(off.ranked()).isFalse();
        assertThat(off.order()).containsExactly(0, 1, 2);
        assertThat(ExcerptPriority.off().enabled()).isFalse();
    }

    @Test
    void equalScoresKeepTheDiscoveryOrder() {
        ExcerptPriority.Priority priority = scoring(0.5, 0.5, 0.7).prioritize("q", hits);

        assertThat(priority.order()).containsExactly(2, 0, 1);
        assertThat(priority.fallback()).isNull();
    }

    @Test
    void onlySourcesApprovedForExternalProcessingAreScoredTheOthersKeepTheirPlace() {
        List<KeywordSearch.Hit> mixed = List.of(hit("wiki", "a", "Kantine", "Urlaub"),
                hit("secret", "s", "Geheimer Plan", "Vertraulich"), hit("wiki", "c", "Reisekosten", "Urlaub"));
        ExcerptPriority priority = new ExcerptPriority((query, documents) -> {
            scored.add(documents);
            return new double[] {0.1, 0.9};
        }, "wiki"::equals);

        assertThat(priority.prioritize("Urlaub", mixed).apply(mixed)).extracting(KeywordSearch.Hit::pageId)
                .containsExactly("c", "s", "a");
        assertThat(scored).singleElement().satisfies(documents -> assertThat(documents)
                .containsExactly("Kantine\nUrlaub", "Reisekosten\nUrlaub"));

        // Nur ein freigegebener Kandidat: nichts zu ordnen, nichts geht hinaus.
        scored.clear();
        assertThat(priority.prioritize("Urlaub", mixed.subList(0, 2)).order()).containsExactly(0, 1);
        assertThat(scored).isEmpty();
    }

    private ExcerptPriority scoring(double... scores) {
        return new ExcerptPriority((query, documents) -> {
            scored.add(documents);
            return scores;
        }, sourceId -> true);
    }

    private static KeywordSearch.Hit hit(String sourceId, String pageId, String title, String snippet) {
        return new KeywordSearch.Hit(sourceId, pageId, "DEV", title, snippet, null, "1", null, null);
    }
}
