package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Semantische Rolle eines Beteiligten an einem {@link Event}: wer handelt, worauf es sich richtet, wer etwas erhält.
 * Nicht die grammatische Funktion: im Passiv ist das grammatische Subjekt das betroffene Objekt.
 */
public enum ParticipantRole {
    /** Wer handelt (im Passiv die Gruppe mit „durch“ oder „von“). */
    ACTOR,
    /** Worauf sich die Handlung richtet oder was sie hervorbringt. */
    AFFECTED_OBJECT,
    /** Wer etwas erhält (oft ein Dativ). */
    RECIPIENT,
    /** Womit die Handlung geschieht. */
    INSTRUMENT,
    /** Wo die Handlung geschieht. */
    LOCATION;

    /** Die Rolle mit diesem Namen oder {@code null}. */
    public static ParticipantRole parse(String name) {
        if (name == null) {
            return null;
        }
        for (ParticipantRole role : values()) {
            if (role.name().equals(name.trim())) {
                return role;
            }
        }
        return null;
    }
}
