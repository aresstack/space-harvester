package com.aresstack.enterpriseai.source.api;

/** Wie es um Dateiabrufe einer Quelle steht ({@link PageViewSource#downloadAccess()}). */
public enum DownloadAccess {
    /** Keine Einmalcode-Abfrage bekannt; Dateien laden mit der normalen Anmeldung (oder es ist noch unbekannt). */
    OPEN,
    /** Die Quelle verlangt für Dateien einen Einmalcode, der für diese Sitzung noch nicht bestätigt ist. */
    ONE_TIME_CODE_REQUIRED,
    /** Ein Einmalcode wurde bestätigt; Dateien laden ohne neue Abfrage, solange die Sitzung gilt. */
    UNLOCKED
}
