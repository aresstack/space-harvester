package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybride Suche (#181, wie {@code ReciprocalRankFusion} im Enterprise AI Client): führt Rangfolgen von Volltext- und
 * Vektorsuche über Reciprocal Rank Fusion zusammen, {@code score = Σ 1 / (k + rang)} mit {@code k} =
 * {@value #RANK_CONSTANT} und gleichem Gewicht je Rangfolge. RRF braucht nur Ränge, keine vergleichbaren Scores.
 * Kandidaten ohne Rang folgen in ihrer bisherigen Reihenfolge; Gleichstände behalten sie ebenfalls. Eine Seite ist
 * {@code sourceId + pageId} und zählt je Rangfolge nur mit ihrem besten Rang.
 */
final class ReciprocalRankFusion {

    /** Der übliche Wert aus der Literatur, wie im Enterprise AI Client. */
    static final int RANK_CONSTANT = 60;

    private ReciprocalRankFusion() {
    }

    /**
     * @param candidates alle Kandidaten in ihrer bisherigen Reihenfolge
     * @param rankings   Rangfolgen, beste zuerst; Seiten außerhalb der Kandidaten zählen nicht
     * @return dieselben Kandidaten, absteigend nach RRF-Score
     */
    static List<SearchHit> fuse(List<SearchHit> candidates, List<List<SearchHit>> rankings) {
        Map<PageKey, Double> scores = new HashMap<>();
        for (List<SearchHit> ranking : rankings) {
            Map<PageKey, Integer> ranks = new HashMap<>();
            for (SearchHit hit : ranking) {
                ranks.putIfAbsent(hit.page().header().key(), ranks.size() + 1);
            }
            ranks.forEach((page, rank) -> scores.merge(page, 1.0 / (RANK_CONSTANT + rank), Double::sum));
        }
        List<SearchHit> fused = new ArrayList<>(candidates);
        // Stabil: Gleichstände und Kandidaten ohne Rang behalten ihre Reihenfolge.
        fused.sort(Comparator.comparingDouble((SearchHit hit) -> scores.getOrDefault(hit.page().header().key(), 0.0))
                .reversed());
        return fused;
    }
}
