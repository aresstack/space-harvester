package com.aresstack.spaceharvester.application.jobs;

/**
 * Abschnitt eines laufenden Auftrags, der Dateien herunterlädt ({@link JobType#download()}): erst vorbereiten (Verweise
 * zählen), dann auf einen Download-Platz warten, dann laden (Angelo 11.11.2026). Vorbereitungen und Downloads haben
 * je eine eigene Grenze ({@link Jobs#parallelPreparations()}, {@link Jobs#parallelDownloads()}).
 */
public enum JobStage {
    /** Zählt die Verweise; belegt einen Platz für Vorbereitungen. */
    PREPARING,
    /** Vorbereitet; wartet auf einen Download-Platz und belegt keinen Platz. */
    PREPARED,
    /** Lädt; belegt einen Download-Platz. */
    DOWNLOADING
}
