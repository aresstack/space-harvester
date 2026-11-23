package com.aresstack.spaceharvester.application.extraction;

/** Woher ein Feldwert stammt. */
public enum ExtractionOrigin {
    /** Muster/RegEx über den Dokumenttext (deterministisch). */
    REGEX,
    /** KI-gestützte semantische Extraktion. */
    AI,
    /** Normalisiert oder berechnet aus anderen Dokumentfakten, nie durch fachliche Regeln ({@link FieldStatus#DERIVED}). */
    DERIVED
}
