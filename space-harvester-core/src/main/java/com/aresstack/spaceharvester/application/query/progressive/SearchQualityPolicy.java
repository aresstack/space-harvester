package com.aresstack.spaceharvester.application.query.progressive;

import java.util.List;

/**
 * Ob nach einer Welle weitergesucht wird (ADR 0017). Austauschbar: Die erste Umsetzung zählt Treffer über einer
 * Schwelle ({@link #minimumRelevantHits}); spätere können Abdeckung der Frage und Erkenntnisgewinn der letzten Welle
 * werten, die {@link State} schon mitbringt. Rohe Werte eines Cross-Encoders sind modellabhängig und nicht kalibriert;
 * eine Schwelle ist deshalb eine Einstellung des Suchprofils, kein fester Wert.
 */
@FunctionalInterface
public interface SearchQualityPolicy {

    /**
     * Stand nach einer Welle.
     *
     * @param scores        Bewertungen aller bisher zusammengeführten Treffer gegen die ursprüngliche Anfrage, beste
     *                      zuerst; Treffer ohne Bewertung fehlen
     * @param hits          wie viele Treffer insgesamt sichtbar sind
     * @param newInLastWave wie viele davon die letzte Welle neu brachte
     * @param searches      wie viele Suchanfragen gelaufen sind, die ursprüngliche eingeschlossen
     */
    record State(List<Double> scores, int hits, int newInLastWave, int searches) {

        public State {
            scores = List.copyOf(scores);
        }
    }

    /** {@code true}: Qualitätsziel erreicht, die Erweiterung endet. */
    boolean satisfied(State state);

    /** Erreicht, sobald mindestens {@code count} Treffer eine Bewertung von mindestens {@code threshold} haben. */
    static SearchQualityPolicy minimumRelevantHits(int count, double threshold) {
        if (count < 1) {
            throw new IllegalArgumentException("count muss mindestens 1 sein");
        }
        return state -> state.scores().stream().filter(score -> score >= threshold).count() >= count;
    }

    /** Nie erreicht: alle Varianten laufen, bis sie aufgebraucht sind oder das Budget endet. */
    static SearchQualityPolicy never() {
        return state -> false;
    }
}
