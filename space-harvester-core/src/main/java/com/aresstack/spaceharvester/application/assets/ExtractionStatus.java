package com.aresstack.spaceharvester.application.assets;

/**
 * Stand einer Extraktion je Dokument und je Eintrag im Verzeichnis enthaltener Dateien (ADR 0026, Punkt 7). Der Status
 * ist ein Datum am Dokument, nie Text in dessen Inhalt.
 */
public enum ExtractionStatus {
    /** Vollständig gelesen. */
    OK,
    /** Teilweise gelesen: Was bis zur Grenze oder zum Fehler gelesen ist, bleibt. */
    PARTIAL,
    /** Das Format hat keinen lesbaren Text. */
    UNSUPPORTED,
    /** Nicht lesbar (defekt, verschlüsselt, Fehler der Extraktion). */
    FAILED,
    /** Bewusst nicht gelesen, wie im Manifest (ADR 0023). */
    EXCLUDED
}
