package com.aresstack.spaceharvester.application.knowledge;

import java.util.Locale;

/**
 * Welcher Aussagenanalysator einen Lauf rechnet (Fahrplan Analyse-Reiter, Paket A2): der MaltParser (Vorgabe) oder der
 * KI-Analysator über den eingestellten KI-Dienst. Beide haben eine eigene Ablage; ihre Ergebnisse überschreiben sich nie
 * und werden nie in einem Graphen gemischt ({@link AnalyzerStores}).
 */
public enum AnalyzerChoice {
    /** Der lokale Satzanalysator (MaltParser in der Desktop-App). */
    PARSER("parser"),
    /** Der KI-Analysator ({@link ModelStatementAnalyzer}); nur Seiten mit KI-Freigabe. */
    MODEL("model");

    private final String value;

    AnalyzerChoice(String value) {
        this.value = value;
    }

    /** Der Wert in der Konfiguration. */
    public String value() {
        return value;
    }

    /** Zu welchem Analysator eine gespeicherte Analyse gehört, nach ihrer Kennung. */
    public static AnalyzerChoice of(String analyzerId) {
        return ModelStatementAnalyzer.ID.equals(analyzerId) ? MODEL : PARSER;
    }

    /**
     * Liest den Wert; fehlend oder leer heißt {@link #PARSER}.
     *
     * @throws IllegalArgumentException bei einem unbekannten Wert
     */
    public static AnalyzerChoice parse(String value) {
        if (value == null || value.isBlank()) {
            return PARSER;
        }
        String text = value.strip().toLowerCase(Locale.ROOT);
        for (AnalyzerChoice choice : values()) {
            if (choice.value.equals(text)) {
                return choice;
            }
        }
        throw new IllegalArgumentException("Erwartet parser oder model");
    }
}
