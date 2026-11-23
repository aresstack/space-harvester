package com.aresstack.spaceharvester.application.extraction;

/** Woher ein Feldwert stammt. */
public enum ExtractionOrigin {
    /** Muster/RegEx über den Dokumenttext (deterministisch). */
    REGEX,
    /** KI-gestützte semantische Extraktion. */
    AI,
    /** Aus anderen Feldern abgeleitet. */
    DERIVED
}
