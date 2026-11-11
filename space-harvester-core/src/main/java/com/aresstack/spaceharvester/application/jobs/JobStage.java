package com.aresstack.spaceharvester.application.jobs;

/**
 * Abschnitt eines laufenden Auftrags, der Dateien herunterlädt ({@link JobType#download()}): erst vorbereiten (Verweise
 * zählen), dann auf einen Download-Platz warten, dann laden (Angelo 11.11.2026). Vorbereitungen und Downloads haben
 * je eine eigene Grenze ({@link Jobs#parallelPreparations()}, {@link Jobs#parallelDownloads()}).
 */
public enum JobStage {
    /**
     * Noch wartend ({@link JobState#QUEUED}): angelegt, aber erst startbereit, wenn der Auftrag fertig ist, dem er folgt,
     * etwa Bilder und Anhänge, bis der Text ihres Exports veröffentlicht ist; belegt keinen Platz.
     */
    AWAITING,
    /** Zählt die Verweise; belegt einen Platz für Vorbereitungen. */
    PREPARING,
    /** Vorbereitet; wartet auf einen Download-Platz und belegt keinen Platz. */
    PREPARED,
    /** Lädt; belegt einen Download-Platz. */
    DOWNLOADING
}
