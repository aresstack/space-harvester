package com.aresstack.spaceharvester.application.access;

/**
 * Der Aufrufer darf das Angefragte nicht lesen oder auslösen. Trägt bewusst weder IDs noch Gründe, damit nichts davon in
 * Antworten oder Logs gelangt.
 */
public class AccessDeniedException extends RuntimeException {

    private final boolean concealExistence;
    private final String sourceId;

    AccessDeniedException(boolean concealExistence, String sourceId) {
        super("Zugriff verweigert", null, false, false);
        this.concealExistence = concealExistence;
        this.sourceId = sourceId;
    }

    /** Quellen-ID aus der Anfrage, damit die verdeckte Antwort der eines unbekannten Objekts gleicht. */
    public String sourceId() {
        return sourceId;
    }

    /**
     * {@code true}, wenn die Antwort nicht verraten darf, dass das Objekt existiert; die API antwortet dann wie bei
     * einem unbekannten Objekt. Das gilt, wenn die Prüfung erst nach dem Lesen bei der Quelle möglich war.
     */
    public boolean concealExistence() {
        return concealExistence;
    }
}
