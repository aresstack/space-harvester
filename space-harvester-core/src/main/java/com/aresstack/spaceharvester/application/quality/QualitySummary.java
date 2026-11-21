package com.aresstack.spaceharvester.application.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Kennzahlen über eine Menge gemessener Fragen. Treffer@k, MRR und Laufzeit zählen nur Fragen mit Antwort; eine
 * gescheiterte Suche zählt dabei als nicht gefunden. Fragen der Art {@link CaseKind#KEINE} zählen getrennt.
 *
 * @param questions         Fragen mit Antwort (ohne {@link CaseKind#KEINE})
 * @param failed            davon gescheitert
 * @param top5              Fragen mit einer erwarteten Fundstelle unter den ersten 5
 * @param topContext        … unter den ersten {@code contextSize} (was der Chat ins Modell gibt)
 * @param top10             … unter den ersten 10
 * @param mrr               Mittel aus 1/bester Rang (0 für nicht gefunden)
 * @param spread            Fragen der Art {@link CaseKind#VERTEILT}
 * @param spreadRecall      deren mittlerer Anteil erwarteter Fundstellen unter den ersten 10
 * @param medianMillis      Laufzeit Median (alle Fragen samt {@link CaseKind#KEINE})
 * @param p90Millis         Laufzeit 90-%-Quantil
 * @param maxMillis         längste Laufzeit
 * @param noAnswer          Fragen der Art {@link CaseKind#KEINE}
 * @param noAnswerCorrect   davon ohne brauchbaren Treffer im Chat-Kontext
 */
public record QualitySummary(int questions, int failed, int top5, int topContext, int top10, double mrr,
                             int spread, double spreadRecall, long medianMillis, long p90Millis, long maxMillis,
                             int noAnswer, int noAnswerCorrect) {

    public static QualitySummary of(List<CaseResult> results, int contextSize) {
        return of(results, contextSize, result -> true);
    }

    public static QualitySummary of(List<CaseResult> results, int contextSize, Predicate<CaseResult> filter) {
        int questions = 0;
        int failed = 0;
        int top5 = 0;
        int topContext = 0;
        int top10 = 0;
        double reciprocal = 0;
        int spread = 0;
        double recall = 0;
        int noAnswer = 0;
        int noAnswerCorrect = 0;
        List<Long> durations = new ArrayList<>();
        for (CaseResult result : results) {
            if (!filter.test(result)) {
                continue;
            }
            durations.add(result.durationMillis());
            if (result.noAnswer()) {
                noAnswer++;
                if (result.noAnswerCorrect()) {
                    noAnswerCorrect++;
                }
                continue;
            }
            questions++;
            if (result.failed()) {
                failed++;
            }
            top5 += result.inTop(5) ? 1 : 0;
            topContext += result.inTop(contextSize) ? 1 : 0;
            top10 += result.inTop(10) ? 1 : 0;
            int best = result.bestRank();
            reciprocal += best > 0 ? 1.0 / best : 0;
            if (result.kinds().contains(CaseKind.VERTEILT)) {
                spread++;
                recall += result.recallTop10();
            }
        }
        Collections.sort(durations);
        return new QualitySummary(questions, failed, top5, topContext, top10,
                questions == 0 ? 0 : reciprocal / questions, spread, spread == 0 ? 0 : recall / spread,
                quantile(durations, 0.5), quantile(durations, 0.9),
                durations.isEmpty() ? 0 : durations.get(durations.size() - 1), noAnswer, noAnswerCorrect);
    }

    /** Nächstgelegener Rang (nearest rank) einer sortierten Liste, {@code 0} wenn leer. */
    static long quantile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** Anteil {@code count} von {@link #questions()}, {@code 0} ohne Fragen. */
    public double share(int count) {
        return questions == 0 ? 0 : (double) count / questions;
    }
}
