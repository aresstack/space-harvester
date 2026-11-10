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
    ENRICHMENT,
    /**
     * Bilder eines veröffentlichten Exports beschaffen und im Asset-Speicher ablegen ({@code assets.json}), als eigener
     * Auftrag; der Export wartet nicht darauf.
     */
    ASSETS,
    /**
     * Dokument-Anhänge eines veröffentlichten Exports beschaffen (ADR 0023, „Anhänge beschaffen“), als eigener Auftrag
     * neben {@link #ASSETS}, mit demselben Download, derselben Anmeldung und derselben Ablage; nur bei eingeschalteten
     * Anhängen.
     */
    ATTACHMENTS,
    /**
     * MaltParser-Aussagenanalyse einer Seite, eines Unterzweigs, Space oder einer Quelle, nur auf ausdrückliche
     * Anforderung in der Desktop-App (#499, ADR 0024); nie Folgeauftrag eines Abgleichs oder Exports.
     */
    STATEMENT_ANALYSIS,
    /**
     * Alle lokal gespeicherten Daten einer Quelle löschen („Daten bereinigen“), ohne die Quelle selbst zu entfernen;
     * beendet vorher die übrigen Aufträge der Quelle.
     */
    PURGE,
    /**
     * Export des Knowledge Graph als JSON-LD 1.1 (#499, ADR 0024); liest nur vorhandene Analysen und stößt nie eine
     * Analyse an.
     */
    KNOWLEDGE_GRAPH_EXPORT;

    /**
     * Folgeauftrag eines Abgleichs oder Exports: startet erst, wenn kein Abgleich oder Export wartet, und belegt nie
     * alle Plätze.
     */
    public boolean followUp() {
        return this == ENRICHMENT || this == ASSETS || this == ATTACHMENTS;
    }

    /**
     * Lässt sich anhalten und im selben Eintrag fortsetzen (ADR 0025): Einlesen und Download legen jede geladene neue
     * Seite sofort ab, ein neuer Lauf holt nur, was fehlt. Bilder ({@link #ASSETS}) legen jede Datei sofort im
     * Asset-Speicher ab und schreiben beim Anhalten das bisherige Manifest; ein neuer Lauf übernimmt geprüfte Seiten.
     * Anhänge ({@link #ATTACHMENTS}) ebenso.
     */
    public boolean resumable() {
        return this == SYNC || this == EXPORT || this == ASSETS || this == ATTACHMENTS;
    }
}
