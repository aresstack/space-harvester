package com.aresstack.spaceharvester.application.configuration;

import java.util.List;
import java.util.Locale;

/**
 * Das Nachdenken eines Schritts mit eigenem Modell (Angelo 22.11.2026: „überall das Nachdenken einstellbar“):
 * {@code none}, {@code low}, {@code medium} oder {@code high}; leer heißt wie das Text-Modell
 * ({@code ai.step-thinking}).
 */
final class StepThinking {

    /** Die erlaubten Stufen, wie sie in der Datei stehen. */
    static final List<String> LEVELS = List.of("none", "low", "medium", "high");

    private StepThinking() {
    }

    /**
     * Die Stufe klein geschrieben; {@code null} oder leer heißt {@code null}.
     *
     * @throws IllegalArgumentException bei einem unbekannten Wert
     */
    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String level = value.strip().toLowerCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException("Nachdenken ist none, low, medium oder high");
        }
        return level;
    }
}
