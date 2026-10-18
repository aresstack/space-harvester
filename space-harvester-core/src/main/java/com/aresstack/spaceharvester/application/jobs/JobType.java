package com.aresstack.spaceharvester.application.jobs;

/** Art eines Auftrags. Alle Arten teilen denselben Status-, Fortschritts- und Abbruchmechanismus. */
public enum JobType {
    /** Ernte in den Cache (#10). */
    HARVEST,
    /** Abgleich des Cache mit der Quelle (#16). */
    SYNC,
    /** Exportartefakt aus dem Cache (#11). */
    EXPORT,
    /**
     * Anreicherungen eines Space nach einem bestätigten Abgleich oder Einlesen, als eigener Auftrag mit eigenem
     * Fortschritt und Abbruch; Abgleich und Export warten nicht darauf.
     */
    ENRICHMENT
}
