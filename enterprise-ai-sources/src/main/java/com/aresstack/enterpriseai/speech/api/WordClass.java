package com.aresstack.enterpriseai.speech.api;

/**
 * Coarse part of speech of a token, neutral of any tagset. Adapters map their tags to it (OpenNLP's German models use
 * STTS); callers draw their own conclusions from it, the adapter decides nothing beyond the word class.
 */
public enum WordClass {

    /** Finite verb, including auxiliaries and modals ({@code ist}, {@code kannst}, {@code muss}). */
    VERB_FINITE,

    /** Imperative ({@code erklär}, {@code zeig}). */
    VERB_IMPERATIVE,

    /** Infinitive or participle ({@code tun}, {@code gemacht}). */
    VERB_NONFINITE,

    /** Common noun. */
    NOUN,

    /** Proper noun or name. */
    PROPER_NOUN,

    /** Personal or reflexive pronoun. */
    PERSONAL_PRONOUN,

    /** Interrogative pronoun, determiner or adverb ({@code was}, {@code welche}, {@code wie}). */
    INTERROGATIVE,

    /** Interjection or answer particle ({@code hallo}, {@code ja}, {@code danke}). */
    INTERJECTION,

    /** Foreign-language material. */
    FOREIGN,

    /** Cardinal number. */
    CARDINAL,

    /** Punctuation. */
    PUNCTUATION,

    /** Anything else (articles, adjectives, adverbs, particles, conjunctions, prepositions). */
    OTHER;

    /** Whether this is a verb of any form. */
    public boolean isVerb() {
        return this == VERB_FINITE || this == VERB_IMPERATIVE || this == VERB_NONFINITE;
    }
}
