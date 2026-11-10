package com.aresstack.spaceharvester.application.terms;

/**
 * Budget eines Synonymlaufs (#336), aus der Konfiguration ({@code space-harvester.terms.synonyms.*}) oder vom
 * Bearbeiter beim Start. Die Laufzeitgrenze setzt der Auftrag selbst ({@code jobs.max-runtime}).
 *
 * @param maxTerms         höchstens so viele Begriffe betrachtet ein Lauf, die mit den meisten Seiten zuerst
 * @param maxEmbeddedTerms höchstens so viele Begriffe gehen mit Kontext an das Embedding-Modell (Stufe B); 0 schaltet
 *                         die semantische Vorauswahl ab
 * @param maxJudgedPairs   höchstens so viele Kandidatenpaare gehen an das Chat-Modell (Stufe C); 0 schaltet die
 *                         KI-Prüfung ab, es bleiben die Regelvorschläge
 */
public record SynonymBudget(int maxTerms, int maxEmbeddedTerms, int maxJudgedPairs) {

    /** Konfigurationsschlüssel unter {@code space-harvester.terms.synonyms}. */
    public static final String PREFIX = "space-harvester.terms.synonyms.";
    public static final String MAX_TERMS = "max-terms";
    public static final String MAX_EMBEDDED_TERMS = "max-embedded-terms";
    public static final String MAX_JUDGED_PAIRS = "max-judged-pairs";

    public static final SynonymBudget DEFAULT = new SynonymBudget(2_000, 500, 200);

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
        return new SynonymBudget(maxTerms, maxEmbeddedTerms, pairs);
    }
}
