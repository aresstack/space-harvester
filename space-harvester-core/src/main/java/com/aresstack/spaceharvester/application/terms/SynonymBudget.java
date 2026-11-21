package com.aresstack.spaceharvester.application.terms;

/**
 * Budget eines Synonymlaufs (#336), aus der Konfiguration ({@code space-harvester.terms.synonyms.*}) oder vom
 * Bearbeiter beim Start. Die Laufzeitgrenze setzt der Auftrag selbst ({@code jobs.max-runtime}).
 *
 * @param maxTerms         höchstens so viele Begriffe betrachtet ein Lauf, die mit den meisten Seiten zuerst
 * @param maxEmbeddedTerms höchstens so viele Begriffe gehen mit Kontext an das Embedding-Modell (Stufe B); 0 schaltet
 *                         die semantische Vorauswahl ab
 * @param maxJudgedPairs   höchstens so viele Kandidatenpaare gehen zusätzlich an das Chat-Modell (Stufe C, optional);
 *                         0 (Standard) lässt die Chat-Prüfung aus, Embedding-Paare werden dann direkt Vorschläge
 * @param automatic        ob nach jeder Anreicherung einer Quelle und nach neuer KI-Freigabe einer Seite ein Lauf für
 *                         die Quelle startet (SY1, Standard an); aus nur noch auf Klick
 * @param glossaryTermsOnly ob nur bestätigte Fachbegriffe des Glossars Kandidaten sind (K3, Angelo 21.11.2026);
 *                         Standard aus: alle Begriffe des Index, die mit den meisten Seiten zuerst
 */
public record SynonymBudget(int maxTerms, int maxEmbeddedTerms, int maxJudgedPairs, boolean automatic,
                            boolean glossaryTermsOnly) {

    /** Konfigurationsschlüssel unter {@code space-harvester.terms.synonyms}. */
    public static final String PREFIX = "space-harvester.terms.synonyms.";
    public static final String MAX_TERMS = "max-terms";
    public static final String MAX_EMBEDDED_TERMS = "max-embedded-terms";
    public static final String MAX_JUDGED_PAIRS = "max-judged-pairs";
    public static final String AUTOMATIC = "automatic";
    public static final String GLOSSARY_TERMS_ONLY = "glossary-terms-only";

    public static final SynonymBudget DEFAULT = new SynonymBudget(2_000, 500, 0, true, false);

    /** Über alle Begriffe des Index (wie vor K3). */
    public SynonymBudget(int maxTerms, int maxEmbeddedTerms, int maxJudgedPairs, boolean automatic) {
        this(maxTerms, maxEmbeddedTerms, maxJudgedPairs, automatic, false);
    }

    /** Mit automatischem Lauf (Standard). */
    public SynonymBudget(int maxTerms, int maxEmbeddedTerms, int maxJudgedPairs) {
        this(maxTerms, maxEmbeddedTerms, maxJudgedPairs, true);
    }

    public SynonymBudget {
        if (maxTerms < 1) {
            throw new IllegalArgumentException(PREFIX + MAX_TERMS + " muss mindestens 1 sein");
        }
        if (maxEmbeddedTerms < 0 || maxJudgedPairs < 0) {
            throw new IllegalArgumentException(PREFIX + MAX_EMBEDDED_TERMS + " und " + PREFIX + MAX_JUDGED_PAIRS
                    + " dürfen nicht negativ sein");
        }
    }

    public SynonymBudget withMaxJudgedPairs(int pairs) {
        return new SynonymBudget(maxTerms, maxEmbeddedTerms, pairs, automatic, glossaryTermsOnly);
    }

    /** Dasselbe Budget, Lauf nach jeder Anreicherung an oder aus. */
    public SynonymBudget withAutomatic(boolean value) {
        return new SynonymBudget(maxTerms, maxEmbeddedTerms, maxJudgedPairs, value, glossaryTermsOnly);
    }

    /** Dasselbe Budget, nur bestätigte Fachbegriffe des Glossars oder alle Begriffe (K3). */
    public SynonymBudget withGlossaryTermsOnly(boolean value) {
        return new SynonymBudget(maxTerms, maxEmbeddedTerms, maxJudgedPairs, automatic, value);
    }
}
