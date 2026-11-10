package com.aresstack.spaceharvester.application.glossary;

/** Den Glossareintrag gibt es nicht oder er ist für den Aufrufer verborgen. */
public class UnknownGlossaryEntryException extends RuntimeException {

    private final String entryId;

    public UnknownGlossaryEntryException(String entryId) {
        super("Unbekannter Glossareintrag " + entryId);
        this.entryId = entryId;
    }

    public String entryId() {
        return entryId;
    }
}
