package com.aresstack.spaceharvester.application.facts;

/** Die erwartete Revision ist nicht die aktuelle; die API antwortet mit 409 und der aktuellen Revision. */
public class FactConflictException extends RuntimeException {

    private final long revision;

    public FactConflictException(long revision) {
        super("Der Fakt wurde inzwischen geändert");
        this.revision = revision;
    }

    /** Die aktuelle Revision; 0, solange niemand den Fakt geprüft hat. */
    public long revision() {
        return revision;
    }
}
