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
    KNOWLEDGE_GRAPH_EXPORT,
    /**
     * „Fachbegriffe ermitteln“ (#122 Stufe 2, ADR 0019): der KI-Lauf des Fachglossars je Quelle, nur auf ausdrücklichen
     * Wunsch; eigener Eintrag in der Auftragsliste. Eingeplant wie ein Folgeauftrag ({@link #followUp()}): er wartet
     * auf Abgleiche und Exporte und belegt nie alle Plätze.
     */
    GLOSSARY,
    /**
     * Import eines JSON-Pakets ({@code export.zip} ab {@code json-package/3}) in den Cache einer Quelle; bestätigt eine
     * Generation wie ein Abgleich, ohne die Quelle zu fragen.
     */
    IMPORT,
    /**
     * „Neu lesen“ (ADR 0026, Punkt 11): liest abgelegte Dokumente einer Quelle ohne Download neu, etwa nach einem
     * Wechsel der Extraktor-Version, nach einem Import oder, beschränkt auf Dokumente mit Status, nach geänderten
     * Grenzen. Der bisherige Stand gilt, bis das neue Lesen Text liefert. Anhaltbar; ein neuer Lauf liest nur, was noch
     * fehlt. Eingeplant wie ein Folgeauftrag ({@link #followUp()}), damit er nie einen Abgleich oder Export aufhält.
     */
    REREAD,
    /**
     * Datei in einem Zielformat (PDF, Word, Markdown, JSON …) aus dem Datenbestand eines Exports im Ausgabeordner
     * erzeugen, als eigener Auftrag mit Status statt still im Hintergrund (Angelo 16.11.2026); wartet, bis sein Export
     * erfolgreich endet ({@link Jobs#submitAfter}). Liest weder Cache noch Quelle.
     */
    CONVERSION,
    /**
     * „Confluence-Metadaten exportieren“ (Angelo 16.11.2026): je Seite der Auswahl Versionshistorie, Bearbeiter,
     * Kommentare, Content Properties und Restriktionen über die dokumentierten REST-Schnittstellen der Quelle, als
     * eigene JSON-Datei im Ausgabeordner; eigene Abrufe neben dem Snapshot, der Seitenexport bleibt unverändert.
     */
    METADATA_EXPORT,
    /**
     * „Analytics exportieren“ (Angelo 16.11.2026): Seitenaufrufe über die internen Endpunkte des
     * Confluence-Analytics-Plugins, als eigene JSON-Datei; Fehler betreffen nur diesen Auftrag.
     */
    ANALYTICS_EXPORT,
    /**
     * „Zusammenfassen“ (ADR 0030, Abschnitt 5): die KI-Zusammenfassung eines Updates einer Quelle, nur auf Wunsch; Titel
     * und Unterschiede nur freigegebener Seiten (ADR 0028). Eingeplant wie ein Folgeauftrag ({@link #followUp()}).
     */
    UPDATE_SUMMARY,
    /**
     * „Aussagen prüfen“ (ADR 0032, FL4): das Chat-Modell beurteilt die Aussagen gespeicherter, aktueller Analysen eines
     * Umfangs gegen ihren Originalsatz; nur auf ausdrückliche Anforderung und nur Seiten mit KI-Freigabe, nie
     * Folgeauftrag einer Analyse. Ein neuer Lauf fragt nur, was in der Ablage des Prüfschritts fehlt.
     */
    STATEMENT_CHECK;

    /**
     * Lädt von der Quelle herunter: Bilder, Anhänge und die Exporte aus der Quelle (Confluence-Metadaten, Analytics;
     * #944). Erst Vorbereitung (Seiten aus dem Bestand bestimmen, ohne Quelle), dann Download, mit je eigener Grenze
     * ({@link JobStage}): Vorbereitung {@code jobs.parallel-preparations}, Download {@code jobs.parallel-downloads},
     * in der App die Auswahl „Vorbereitung“ und „Downloads“ der Auftragsliste. Belegt keinen der Plätze für Abgleiche
     * und Exporte ({@code jobs.max-concurrent}). Die Verbindungen je Download („Verbindungen“) teilen nur Bilder und
     * Anhänge unter sich auf; ein Export aus der Quelle fragt mit einer Verbindung, Seite für Seite.
     */
    public boolean download() {
        return this == ASSETS || this == ATTACHMENTS || this == METADATA_EXPORT || this == ANALYTICS_EXPORT;
    }

    /**
     * Folgeauftrag eines Abgleichs oder Exports: startet erst, wenn kein Abgleich oder Export wartet, und belegt nie
     * alle Plätze.
     */
    public boolean followUp() {
        return this == ENRICHMENT || this == ASSETS || this == ATTACHMENTS || this == GLOSSARY || this == REREAD
                || this == UPDATE_SUMMARY;
    }

    /**
     * Lässt sich anhalten und im selben Eintrag fortsetzen (ADR 0025): Einlesen und Download legen jede geladene neue
     * Seite sofort ab, ein neuer Lauf holt nur, was fehlt. Bilder ({@link #ASSETS}) legen jede Datei sofort im
     * Asset-Speicher ab und schreiben beim Anhalten das bisherige Manifest; ein neuer Lauf übernimmt geprüfte Seiten.
     * Anhänge ({@link #ATTACHMENTS}) ebenso. „Neu lesen“ ({@link #REREAD}) überspringt beim neuen Lauf, was schon neu
     * gelesen ist. „Zusammenfassen“ ({@link #UPDATE_SUMMARY}) legt erst am Ende ab und fragt beim neuen Lauf von vorn.
     * Metadaten ({@link #METADATA_EXPORT}) legen jede abgefragte Seite sofort in einer Teildatei ab und fragen beim
     * neuen Lauf nur Seiten ohne Daten; Analytics ({@link #ANALYTICS_EXPORT}) fragt von vorn (Angelo 17.11.2026).
     */
    public boolean resumable() {
        return this == SYNC || this == EXPORT || this == ASSETS || this == ATTACHMENTS || this == REREAD
                || this == UPDATE_SUMMARY || this == METADATA_EXPORT || this == ANALYTICS_EXPORT;
    }

    /** Export aus der Quelle per REST-Abfrage (Metadaten, Analytics): Gescheitertes fragt ein neuer Lauf erneut. */
    public boolean sourceQuery() {
        return this == METADATA_EXPORT || this == ANALYTICS_EXPORT;
    }
}
