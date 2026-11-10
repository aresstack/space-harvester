package com.aresstack.spaceharvester.application.jobs;

/** Warum ein Auftrag angehalten ist ({@link JobState#PAUSED}); in jedem Fall setzt ▶ ihn fort. */
public enum PauseReason {
    /** Der Benutzer hat angehalten. */
    USER,
    /** Die Quelle verlangt eine Anmeldung (Einmalcode), die nicht rechtzeitig kam oder abgelehnt wurde. */
    SIGN_IN_REQUIRED,
    /** Die Quelle war mehrmals nacheinander nicht erreichbar oder drosselt. */
    CONNECTION_LOST
}
