package com.aresstack.spaceharvester.nlp.extraction;

/** Rang eines Beteiligten für die priorisierte Sicht: zuerst der Kern, Ergänzungen und Umstände bei Bedarf. */
public enum Priority {
    /** Subjekt und Akkusativobjekt. */
    CORE,
    /** Weitere Argumente des Prädikats (Empfänger, Präpositionalobjekt, Handelnder im Passiv). */
    SUPPLEMENTARY,
    /** Adverbiale Angaben wie Zeit und Umstände. */
    CONTEXT
}
