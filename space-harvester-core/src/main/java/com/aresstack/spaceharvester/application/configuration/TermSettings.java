package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.terms.SynonymBudget;

/**
 * Einstellungen des Begriffsindex (ADR 0019): das Budget der Synonymerkennung (#336) unter
 * {@code space-harvester.terms.synonyms.*} und die Regeln der Begriffsableitung für das Fachglossar (ADR 0032, FL5b)
 * unter {@code space-harvester.glossary.rules.*}. Wirkt beim nächsten Lauf; Server und App lesen dieselben Schlüssel.
 *
 * @param synonyms      Budget eines Synonymlaufs
 * @param glossaryRules Regeln der Begriffsableitung
 */
public record TermSettings(SynonymBudget synonyms, GlossaryRuleSettings glossaryRules) {

    public static final String SYNONYMS_MAX_TERMS = "terms.synonyms." + SynonymBudget.MAX_TERMS;
    public static final String SYNONYMS_MAX_EMBEDDED_TERMS = "terms.synonyms." + SynonymBudget.MAX_EMBEDDED_TERMS;
    public static final String SYNONYMS_MAX_JUDGED_PAIRS = "terms.synonyms." + SynonymBudget.MAX_JUDGED_PAIRS;
    public static final String SYNONYMS_AUTOMATIC = "terms.synonyms." + SynonymBudget.AUTOMATIC;

    public static final TermSettings DEFAULT = new TermSettings(SynonymBudget.DEFAULT, GlossaryRuleSettings.DEFAULT);

    /** Mit den Vorgaben der Begriffsableitung ({@link GlossaryRuleSettings#DEFAULT}). */
    public TermSettings(SynonymBudget synonyms) {
        this(synonyms, GlossaryRuleSettings.DEFAULT);
    }

    public TermSettings {
        synonyms = synonyms == null ? SynonymBudget.DEFAULT : synonyms;
        glossaryRules = glossaryRules == null ? GlossaryRuleSettings.DEFAULT : glossaryRules;
    }

    /** Dieselben Einstellungen mit anderen Regeln der Begriffsableitung. */
    public TermSettings withGlossaryRules(GlossaryRuleSettings value) {
        return new TermSettings(synonyms, value);
    }
}
