package com.aresstack.spaceharvester.application.jobs;

/** Warum ein Auftrag angehalten ist ({@link JobState#PAUSED}); in jedem Fall setzt ▶ ihn fort. */
public enum PauseReason {
    /** Der Benutzer hat angehalten. */
    USER,
    /** Die Quelle verlangt eine Anmeldung (Einmalcode), die nicht rechtzeitig kam oder abgelehnt wurde. */
    SIGN_IN_REQUIRED,
    /** Die Quelle war mehrmals nacheinander nicht erreichbar oder drosselt. */
    CONNECTION_LOST,
    /**
     * Der Durchgang hält für „Nur Fehler neu versuchen“ an und läuft sofort im selben Eintrag neu
     * ({@link Jobs#requestRetryFailed}); nur solange das Anhalten angefordert ist, angehalten und abgelegt wird daraus
     * {@link #USER}.
     */
    RETRY,
    /**
     * Nach einem Neustart nicht von selbst fortgesetzt, weil ein anderer Aufrufer den Auftrag gestartet hat als der
     * wiederhergestellte (Aussagenanalyse als Hintergrundart, Fahrplan D3); nur ▶ von Hand setzt ihn fort.
     */
    RESUME_BY_HAND
}
