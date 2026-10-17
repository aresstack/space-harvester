package com.aresstack.spaceharvester.nlp.extraction;

/**
 * Einstellungen der objektzentrierten Auswertung.
 *
 * @param caseFallback Kasus-Rückfall ({@link CaseFallback}) anwenden; seine Ergebnisse sind immer als Heuristik
 *                     markiert
 */
public record ExtractionSettings(boolean caseFallback) {

    public static ExtractionSettings defaults() {
        return new ExtractionSettings(true);
    }
}
