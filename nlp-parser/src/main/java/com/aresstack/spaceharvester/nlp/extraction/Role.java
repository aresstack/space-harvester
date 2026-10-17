package com.aresstack.spaceharvester.nlp.extraction;

/**
 * Semantische Rolle eines Beteiligten einer Aussage, wie im Aussagenmodell aus enterprise-ai-client und in #29;
 * nicht die grammatische Funktion (die steht in {@link Argument#relation()}): im Passiv ist das Subjekt betroffen.
 */
public enum Role {
    /** Wer handelt: aktives Subjekt, im Passiv die von-/durch-Gruppe ({@code obl:agent}). */
    ACTOR,
    /** Worauf die Handlung wirkt: Akkusativobjekt, im Passiv das Subjekt. */
    AFFECTED_OBJECT,
    /** Wer etwas erhält: Dativargument ({@code obl:arg} ohne Präposition, {@code iobj}). */
    RECIPIENT,
    /** Präpositionalobjekt ({@code obl:arg} mit Präposition). */
    PREPOSITIONAL_OBJECT,
    /** Womit gehandelt wird ({@code mit}, {@code mittels}, {@code per}); Wortliste. */
    INSTRUMENT,
    /** Zeitangabe ({@code am Montag}); Wortliste. */
    TIME,
    /** Andere adverbiale Ergänzung ({@code obl}). */
    CIRCUMSTANCE
}
