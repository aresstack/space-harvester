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
 */
public record CheckSettings(CheckScope scope, int parallel, String model) {

    public static final String SCOPE = "statements.check.scope";
    public static final String PARALLEL = "statements.check.parallel";
    public static final String MODEL = "statements.check.model";
    public static final int DEFAULT_PARALLEL = 2;
    public static final CheckSettings DEFAULT = new CheckSettings(CheckScope.UNCERTAIN_AND_STAGE, DEFAULT_PARALLEL,
            null);

    public CheckSettings {
        scope = scope == null ? CheckScope.UNCERTAIN_AND_STAGE : scope;
        if (parallel < 1) {
            throw new IllegalArgumentException("Mindestens ein Satz zugleich");
        }
        model = model == null || model.isBlank() ? null : model.strip();
    }
}
