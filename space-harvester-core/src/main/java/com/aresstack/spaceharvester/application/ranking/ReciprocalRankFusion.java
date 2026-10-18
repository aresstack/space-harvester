package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybride Suche (#181, wie {@code ReciprocalRankFusion} mit {@code RetrievalSettings} im Enterprise AI Client): führt
 * die Rangfolgen von Volltext- und Vektorsuche über Reciprocal Rank Fusion zusammen,
 * {@code score = keywordWeight / (rankConstant + rang_volltext) + semanticWeight / (rankConstant + rang_vektor)}. RRF
 * braucht nur Ränge, keine vergleichbaren Scores. Kandidaten ohne Rang folgen in ihrer bisherigen Reihenfolge;
 * Gleichstände behalten sie ebenfalls. Eine Seite ist {@code sourceId + pageId} und zählt je Rangfolge nur mit ihrem
 * besten Rang.
 *
 * @param keywordWeight  Gewicht der Volltext-Rangfolge, 0 bis 10
 * @param semanticWeight Gewicht der Kosinus-Rangfolge, 0 bis 10
 * @param rankConstant   {@code k}, 1 bis 1000; 60 ist der übliche Wert aus der Literatur
 */
public record ReciprocalRankFusion(double keywordWeight, double semanticWeight, int rankConstant) {

    /** Wie im Enterprise AI Client: gleiche Gewichte, {@code k = 60}. */
    public static final ReciprocalRankFusion DEFAULT = new ReciprocalRankFusion(1.0, 1.0, 60);

    public ReciprocalRankFusion {
        if (!(keywordWeight >= 0 && keywordWeight <= 10) || !(semanticWeight >= 0 && semanticWeight <= 10)) {
            throw new IllegalArgumentException("Gewichte müssen zwischen 0 und 10 liegen");
        }
        if (rankConstant < 1 || rankConstant > 1000) {
            throw new IllegalArgumentException("rankConstant muss zwischen 1 und 1000 liegen");
        }
    }

    /** Kennung für die Antwort, z. B. {@code rrf} mit den Standardwerten, sonst mit Gewichten und {@code k}. */
    String id() {
        return equals(DEFAULT) ? "rrf" : "rrf@k" + rankConstant + ",w" + keywordWeight + "/" + semanticWeight;
    }

    /**
     * @param candidates alle Kandidaten in ihrer bisherigen Reihenfolge
     * @param keyword    Volltext-Rangfolge, beste zuerst; Seiten außerhalb der Kandidaten zählen nicht
     * @param semantic   Kosinus-Rangfolge, beste zuerst
     * @return dieselben Kandidaten, absteigend nach RRF-Score
     */
    List<SearchHit> fuse(List<SearchHit> candidates, List<SearchHit> keyword, List<SearchHit> semantic) {
        Map<PageKey, Double> scores = new HashMap<>();
        add(scores, keyword, keywordWeight);
        add(scores, semantic, semanticWeight);
        List<SearchHit> fused = new ArrayList<>(candidates);
        // Stabil: Gleichstände und Kandidaten ohne Rang behalten ihre Reihenfolge.
        fused.sort(Comparator.comparingDouble((SearchHit hit) -> scores.getOrDefault(hit.page().header().key(), 0.0))
                .reversed());
        return fused;
    }

    private void add(Map<PageKey, Double> scores, List<SearchHit> ranking, double weight) {
        Map<PageKey, Integer> ranks = new HashMap<>();
        for (SearchHit hit : ranking) {
            ranks.putIfAbsent(hit.page().header().key(), ranks.size() + 1);
        }
        ranks.forEach((page, rank) -> scores.merge(page, weight / (rankConstant + rank), Double::sum));
    }
}
