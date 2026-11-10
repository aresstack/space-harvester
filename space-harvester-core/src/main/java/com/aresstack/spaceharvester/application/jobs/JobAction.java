package com.aresstack.spaceharvester.application.jobs;

/**
 * Was ein Benutzer mit einem Auftrag tun kann. Welche Aktionen gerade erlaubt sind, entscheidet allein der Zustand
 * ({@link JobLifecycleState#actions()}); die Oberfläche zeichnet nur diese Liste.
 */
public enum JobAction {
    /** Einen wartenden Auftrag an den Anfang der Warteschlange ziehen ({@link Jobs#prioritize}). */
    START_NOW,
    /** Einen wartenden oder laufenden Auftrag anhalten; Geladenes bleibt ({@link Jobs#pause}, ADR 0025). */
    PAUSE,
    /**
     * Einen angehaltenen, gescheiterten oder abgebrochenen Auftrag fortsetzen: im selben Eintrag, solange er noch
     * ausführbar ist, sonst nach einem Neustart als gleichwertiger neuer Auftrag aus seinen
     * {@link JobStatus#parameters()}. Beides holt nur, was noch fehlt.
     */
    RESUME,
    /** Einen wartenden oder laufenden Auftrag abbrechen. */
    CANCEL,
    /** Einen beendeten Auftrag aus der Liste entfernen; geladene Daten bleiben. */
    DELETE
}
