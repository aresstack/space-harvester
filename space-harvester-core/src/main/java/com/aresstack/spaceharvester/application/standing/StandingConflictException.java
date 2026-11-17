package com.aresstack.spaceharvester.application.standing;

/** Die erwartete Revision einer Entscheidung ist nicht die aktuelle (wie {@code FactConflictException}). */
public class StandingConflictException extends RuntimeException {

    private final long revision;

    public StandingConflictException(long revision) {
        super("Die Entscheidung wurde inzwischen geändert");
        this.revision = revision;
    }

    /** Die aktuelle Revision; 0 ohne Entscheidung. */
    public long revision() {
        return revision;
    }
}
