package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.terms.SynonymBudget;

/**
 * Einstellungen des Begriffsindex (ADR 0019): das Budget der Synonymerkennung (#336) unter
 * {@code space-harvester.terms.synonyms.*}. Wirkt beim nächsten Lauf; Server und App lesen dieselben Schlüssel.
 *
 * @param synonyms Budget eines Synonymlaufs
 */
public record TermSettings(SynonymBudget synonyms) {

    public static final String SYNONYMS_MAX_TERMS = "terms.synonyms." + SynonymBudget.MAX_TERMS;
    public static final String SYNONYMS_MAX_EMBEDDED_TERMS = "terms.synonyms." + SynonymBudget.MAX_EMBEDDED_TERMS;
    public static final String SYNONYMS_MAX_JUDGED_PAIRS = "terms.synonyms." + SynonymBudget.MAX_JUDGED_PAIRS;

    public static final TermSettings DEFAULT = new TermSettings(SynonymBudget.DEFAULT);

    public TermSettings {
        synonyms = synonyms == null ? SynonymBudget.DEFAULT : synonyms;
    }
}
