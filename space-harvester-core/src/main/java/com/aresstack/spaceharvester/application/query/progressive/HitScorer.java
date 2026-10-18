package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.query.ExcerptPriority;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bewertet Treffer nach Titel und Auszug gegen die ursprüngliche Anfrage, nie gegen die Variante, die sie fand
 * (ADR 0017). Ein Wert je Treffer, höher ist besser, {@link Double#NaN} ohne Bewertung.
 */
@FunctionalInterface
public interface HitScorer {

    double[] score(String original, List<KeywordSearch.Hit> hits);

    /** Ohne Bewertung: Treffer bleiben in der Reihenfolge, in der sie freigegeben wurden. */
    static HitScorer none() {
        return (original, hits) -> {
            double[] scores = new double[hits.size()];
            Arrays.fill(scores, Double.NaN);
            return scores;
        };
    }

    /** Der Cross-Encoder des KI-Dienstes, nur für Quellen mit Freigabe (ADR 0011). */
    static HitScorer of(ExcerptPriority excerpts) {
        Objects.requireNonNull(excerpts, "excerpts");
        return excerpts::scores;
    }
}
