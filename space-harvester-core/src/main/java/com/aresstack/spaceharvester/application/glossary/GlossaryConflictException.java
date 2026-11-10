package com.aresstack.spaceharvester.application.glossary;

/** Der Glossareintrag hat inzwischen eine andere Revision als die, die der Bearbeiter gesehen hat. */
public class GlossaryConflictException extends RuntimeException {

    private final String entryId;
    private final long revision;

    public GlossaryConflictException(String entryId, long revision) {
        super("Glossareintrag " + entryId + " hat inzwischen Revision " + revision);
        this.entryId = entryId;
        this.revision = revision;
    }

    public String entryId() {
        return entryId;
    }

    public long revision() {
        return revision;
    }
}
