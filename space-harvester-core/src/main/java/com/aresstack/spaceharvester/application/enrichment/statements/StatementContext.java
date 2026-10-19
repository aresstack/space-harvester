package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Unter welchen Umständen eine Aussage gilt, jeder Teil im eigenen Satz verankert: eine Verneinung („nicht“), eine
 * Modalität („kann“, „muss“) oder eine Bedingung („wenn der Betrag … übersteigt“). Eine Aussage mit Kontext ist kein
 * allgemeiner Fakt; wer sie liest, darf den Kontext nicht weglassen.
 *
 * @param negation  die verneinenden Wörter oder {@code null}
 * @param modality  die modalen Wörter oder {@code null}
 * @param condition die Bedingung oder {@code null}
 */
public record StatementContext(GroundedText negation, GroundedText modality, GroundedText condition) {

    private static final StatementContext NONE = new StatementContext(null, null, null);

    public static StatementContext none() {
        return NONE;
    }

    /** Weder verneint noch modal noch bedingt. */
    public boolean isPlain() {
        return negation == null && modality == null && condition == null;
    }
}
