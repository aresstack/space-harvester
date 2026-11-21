package com.aresstack.spaceharvester.application.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vergleich eines Laufs mit einem früheren Ergebnis: Kennzahlen vorher/jetzt und je Frage, ob sie besser oder
 * schlechter wurde. Verglichen werden Fragen gleicher ID; besser heißt kleinerer bester Rang (nicht gefunden ist
 * schlechter als jeder Rang), bei {@link CaseKind#KEINE} „zu Recht leer“ statt vorher nicht.
 *
 * @param before  Kennzahlen des früheren Ergebnisses (mit dessen Chat-Kontext)
 * @param after   Kennzahlen dieses Laufs
 * @param changes Fragen, deren Ergebnis sich geändert hat, in der Reihenfolge dieses Laufs; danach entfallene
 */
public record QualityComparison(QualitySummary before, QualitySummary after, List<Change> changes) {

    public enum Direction {
        BETTER,
        WORSE,
        NEW,
        REMOVED
    }

    /**
     * @param id     Fragen-ID
     * @param from   bester Rang vorher ({@code 0} = nicht gefunden, {@code -1} = nicht gemessen); bei
     *               {@link CaseKind#KEINE} 1 = zu Recht leer, 0 = nicht
     * @param to     dasselbe für jetzt
     * @param way    Richtung
     */
    public record Change(String id, int from, int to, Direction way) {
    }

    public QualityComparison {
        changes = List.copyOf(changes);
    }

    public static QualityComparison of(QualityResults.Stored previous, List<CaseResult> current, int contextSize) {
        Map<String, CaseResult> before = new LinkedHashMap<>();
        for (CaseResult result : previous.results()) {
            before.put(result.id(), result);
        }
        List<Change> changes = new ArrayList<>();
        for (CaseResult now : current) {
            CaseResult then = before.remove(now.id());
            if (then == null) {
                changes.add(new Change(now.id(), -1, value(now), Direction.NEW));
                continue;
            }
            int from = value(then);
            int to = value(now);
            int order = Integer.compare(goodness(to, now), goodness(from, then));
            if (order > 0) {
                changes.add(new Change(now.id(), from, to, Direction.BETTER));
            } else if (order < 0) {
                changes.add(new Change(now.id(), from, to, Direction.WORSE));
            }
        }
        for (CaseResult gone : before.values()) {
            changes.add(new Change(gone.id(), value(gone), -1, Direction.REMOVED));
        }
        return new QualityComparison(QualitySummary.of(previous.results(), previous.info().contextSize()),
                QualitySummary.of(current, contextSize), changes);
    }

    private static int value(CaseResult result) {
        return result.noAnswer() ? (result.noAnswerCorrect() ? 1 : 0) : result.bestRank();
    }

    /** Größer ist besser. */
    private static int goodness(int value, CaseResult result) {
        if (result.noAnswer()) {
            return value;
        }
        return value == 0 ? Integer.MIN_VALUE : -value;
    }
}
