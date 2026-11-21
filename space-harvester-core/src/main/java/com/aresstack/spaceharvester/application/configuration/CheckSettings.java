package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.check.CheckScope;

/**
 * Prüfschritt der Aussagenanalyse (ADR 0032, FL4) unter {@code space-harvester.statements.check}: Server und App lesen
 * dieselben Schlüssel. Der Prüfschritt läuft nur im ausdrücklich gestarteten Auftrag und nur für Seiten mit
 * KI-Freigabe; diese Einstellungen sagen nur, wie.
 *
 * @param scope    welche Aussagen das Modell sieht: {@code uncertain-and-stage} (Standard) Aussagen unter PLAUSIBLE und
 *                 alle der Vorstufe, {@code all} alle außer den ungültigen
 * @param parallel so viele Sätze einer Seite höchstens zugleich beim Modell, mindestens 1 (Standard 2)
 * @param model    eigenes Chat-Modell für diesen Schritt oder {@code null} für {@code ai.chat-model}
 * @param maxTokens Tokenlänge (Ausgabelimit) dieses Schritts oder {@code null} für {@code ai.chat-max-tokens}
 */
public record CheckSettings(CheckScope scope, int parallel, String model, Integer maxTokens) {

    public static final String SCOPE = "statements.check.scope";
    public static final String PARALLEL = "statements.check.parallel";
    public static final String MODEL = "statements.check.model";
    public static final String MAX_TOKENS = "statements.check.max-tokens";
    public static final int DEFAULT_PARALLEL = 2;
    public static final CheckSettings DEFAULT = new CheckSettings(CheckScope.UNCERTAIN_AND_STAGE, DEFAULT_PARALLEL,
            null, null);

    public CheckSettings {
        scope = scope == null ? CheckScope.UNCERTAIN_AND_STAGE : scope;
        if (parallel < 1) {
            throw new IllegalArgumentException("Mindestens ein Satz zugleich");
        }
        model = model == null || model.isBlank() ? null : model.strip();
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 1_000_000)) {
            throw new IllegalArgumentException("Tokenlänge zwischen 1 und 1000000");
        }
    }

    /** Ohne eigene Tokenlänge. */
    public CheckSettings(CheckScope scope, int parallel, String model) {
        this(scope, parallel, model, null);
    }

    /** Dieselben Einstellungen mit anderer Tokenlänge; {@code null} heißt {@code ai.chat-max-tokens}. */
    public CheckSettings withMaxTokens(Integer value) {
        return new CheckSettings(scope, parallel, model, value);
    }
}
