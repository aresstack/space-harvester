package com.aresstack.spaceharvester.application.configuration;

import java.util.Locale;

/**
 * Vorstufe „Sätze vereinfachen“ der Aussagenanalyse (ADR 0032, FL3) unter {@code space-harvester.statements.simplification}:
 * Server und App lesen dieselben Schlüssel. Die Vorstufe läuft nur im ausdrücklich gestarteten Auftrag und nur für
 * Seiten mit KI-Freigabe; diese Einstellungen sagen nur, wie.
 *
 * @param mode     welche Sätze vereinfacht werden, wenn der Auftrag die Vorstufe nutzt: {@code where-needed} (Standard)
 *                 nur Stichpunkte, Kennungszeilen und Sätze ohne sichere Aussage, {@code always} alle
 * @param parallel so viele Abschnitte einer Seite höchstens zugleich beim Modell, mindestens 1 (Standard 2)
 * @param model    eigenes Chat-Modell für diesen Schritt oder {@code null} für {@code ai.chat-model}
 * @param maxTokens Tokenlänge (Ausgabelimit) dieses Schritts oder {@code null} für {@code ai.chat-max-tokens}
 */
public record SimplificationSettings(Mode mode, int parallel, String model, Integer maxTokens) {

    public static final String MODE = "statements.simplification.mode";
    public static final String PARALLEL = "statements.simplification.parallel";
    public static final String MODEL = "statements.simplification.model";
    public static final String MAX_TOKENS = "statements.simplification.max-tokens";
    public static final int DEFAULT_PARALLEL = 2;
    public static final SimplificationSettings DEFAULT = new SimplificationSettings(Mode.WHERE_NEEDED,
            DEFAULT_PARALLEL, null, null);

    /** Betriebsart der Vorstufe. */
    public enum Mode {
        /** Nur wo nötig: Stichpunkte, Kennungszeilen und Sätze ohne sichere Aussage. */
        WHERE_NEEDED("where-needed"),
        /** Immer: alle Sätze mit Verb. */
        ALWAYS("always");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        /** Der Wert in der Konfiguration. */
        public String value() {
            return value;
        }

        /**
         * Liest den Wert; fehlend oder leer heißt {@link #WHERE_NEEDED}.
         *
         * @throws IllegalArgumentException bei einem unbekannten Wert
         */
        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return WHERE_NEEDED;
            }
            String text = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Mode mode : values()) {
                if (mode.value.equals(text)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Erwartet where-needed oder always");
        }
    }

    public SimplificationSettings {
        mode = mode == null ? Mode.WHERE_NEEDED : mode;
        if (parallel < 1) {
            throw new IllegalArgumentException("Mindestens ein Abschnitt zugleich");
        }
        model = model == null || model.isBlank() ? null : model.strip();
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 1_000_000)) {
            throw new IllegalArgumentException("Tokenlänge zwischen 1 und 1000000");
        }
    }

    /** Ohne eigene Tokenlänge. */
    public SimplificationSettings(Mode mode, int parallel, String model) {
        this(mode, parallel, model, null);
    }

    /** Dieselben Einstellungen mit anderer Tokenlänge; {@code null} heißt {@code ai.chat-max-tokens}. */
    public SimplificationSettings withMaxTokens(Integer value) {
        return new SimplificationSettings(mode, parallel, model, value);
    }
}
