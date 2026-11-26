package com.aresstack.spaceharvester.application.evidence;

/**
 * Erkenntnisebene (ADR 0041, Punkt 7). Die Ebenen gehen nie still ineinander über: Ein Parserfakt wird nicht von
 * selbst zur Fachregel, eine KI-Hypothese nicht von selbst zur bestätigten Erkenntnis.
 */
public enum InsightLevel {
    /** Deterministischer technischer Fakt des Parsers, etwa die Bedingung {@code IF KZ-STATUS = 'A'}. */
    PARSER_FACT,
    /** Fachliche Deutung eines Modells oder Regelkandidat, noch nicht von einem Menschen bestätigt. */
    HYPOTHESIS,
    /** Von einem Menschen für genau diesen Stand des Belegs bestätigt. */
    CONFIRMED,
    /** Von einem Menschen verworfen; zählt nicht, bleibt aber sichtbar, damit die Prüfung zurückgenommen werden kann. */
    REJECTED
}
