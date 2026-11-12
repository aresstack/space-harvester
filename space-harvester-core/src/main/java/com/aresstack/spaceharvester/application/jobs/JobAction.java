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
    /**
     * „Nur Fehler neu versuchen“ (↻): bei einem mit Fehlern fertigen Download nur die gescheiterten Seiten gezielt
     * nachladen, ohne neue Inventur, im selben Eintrag; das Ergebnis ersetzt das bisherige erst nach Erfolg
     * (ADR 0025).
     */
    RETRY_FAILED,
    /**
     * „Neu erzwingen“ (⟲): bei einem beendeten Download den ganzen Umfang neu von der Quelle abrufen, auch unveränderte
     * Seiten, im selben Eintrag; der bisherige Bestand und das bisherige Ergebnis werden erst nach dem Lauf ersetzt,
     * ein Abbruch zerstört nichts (ADR 0025).
     */
    FORCE_REFRESH,
    /** Einen wartenden oder laufenden Auftrag abbrechen. */
    CANCEL,
    /** Einen beendeten Auftrag aus der Liste entfernen; geladene Daten bleiben. */
    DELETE
}
