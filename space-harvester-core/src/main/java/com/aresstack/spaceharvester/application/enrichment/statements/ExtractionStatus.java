package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Wie weit es eine maschinell gefundene Aussage gebracht hat (#108, wie im Enterprise AI Client); getrennt von der
 * Prüfung durch eine Person ({@code FactReview}), die kein Modell setzt.
 */
public enum ExtractionStatus {
    /** Strukturell gültig und im Text verankert (Wortbereiche, Satz, Prädikat), sonst nicht geprüft. */
    EXTRACTED,
    /** Eine zweite, unabhängige Modellanfrage bestätigt, dass der Satz genau das sagt. */
    MODEL_VALIDATED,
    /** Die zweite Anfrage konnte nicht entscheiden; nur Kandidat. */
    UNCERTAIN,
    /** Ein Beteiligter ist ein Pronomen oder ein anderer, nicht aufgelöster Bezug; nichts wurde erraten. */
    UNRESOLVED_REFERENCE,
    /** Maschinell verworfen: die Prüfung verneinte die Aussage. Bleibt nicht als Aussage. */
    REJECTED
}
