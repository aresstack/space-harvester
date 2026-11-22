package com.aresstack.spaceharvester.deck.ftp.jes;

/** Filter nach Jobzustand für {@code SITE JESSTATUS=…}. */
public enum JobStatus {
    ALL("Alle"),
    OUTPUT("Fertig"),
    ACTIVE("Laufend"),
    INPUT("Wartend");

    private final String label;

    JobStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
