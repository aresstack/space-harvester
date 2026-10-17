package com.aresstack.spaceharvester.application.enrichment.language;

/** Grobe Wortklasse eines Tokens, nur aus Lexikon, Schreibung und Stellung bestimmt, ohne Sprachmodell. */
enum WordClass {
    DETERMINER,
    PRONOUN,
    PREPOSITION,
    CONJUNCTION,
    AUXILIARY,
    MODAL,
    NEGATION,
    ADVERB,
    /** Großgeschriebenes Wort, das kein Funktionswort ist. */
    NOUN,
    /** Mehrere Großbuchstaben, z. B. {@code API}, {@code GmbH}, {@code IDs}. */
    ACRONYM,
    /** Kennung aus Buchstaben mit Ziffern, Unterstrich, Punkt oder Binnenmajuskel, z. B. {@code ABC-1234}. */
    IDENTIFIER,
    NUMBER,
    PUNCTUATION,
    /** Kleingeschriebenes Inhaltswort: Adjektiv, Verb oder anderes. */
    OTHER;

    /** Kann Kern einer Nominalgruppe sein. */
    boolean nominal() {
        return this == NOUN || this == ACRONYM || this == IDENTIFIER;
    }
}
