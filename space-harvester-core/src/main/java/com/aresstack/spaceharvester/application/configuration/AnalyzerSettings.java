package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;

/**
 * Aussagenanalyse (Fahrplan Analyse-Reiter, Paket A2/A3) unter {@code space-harvester.statements}: Server und App lesen
 * dieselben Schlüssel. {@link #analyzer} ist zugleich der vorgewählte Analysator eines Laufs und die Ablage, aus der
 * Graph, Stand, Begriffe und Export lesen (immer genau eine). Der KI-Analysator läuft nur im ausdrücklich gestarteten
 * Auftrag und nur für Seiten mit KI-Freigabe.
 *
 * @param analyzer {@code parser} (Standard, MaltParser) oder {@code model} (Sprachmodell, Erprobung)
 * @param model    eigenes Chat-Modell für den KI-Analysator oder {@code null} für {@code ai.chat-model}
 */
public record AnalyzerSettings(AnalyzerChoice analyzer, String model) {

    public static final String ANALYZER = "statements.analyzer";
    public static final String MODEL = "statements.analyzer-model";
    public static final AnalyzerSettings DEFAULT = new AnalyzerSettings(AnalyzerChoice.PARSER, null);

    public AnalyzerSettings {
        analyzer = analyzer == null ? AnalyzerChoice.PARSER : analyzer;
        model = model == null || model.isBlank() ? null : model.strip();
    }

    public AnalyzerSettings withAnalyzer(AnalyzerChoice value) {
        return new AnalyzerSettings(value, model);
    }
}
