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
    STATEMENT_CHECK,
    /**
     * „Transkript ziehen“ für „In PPTX umwandeln“ (Angelo 20.11.2026): das abgelegte Transkript eines Videos, von der
     * App lokal vervollständigt; läuft zugleich mit {@link #VIDEO_SLIDES}. Fortsetzen setzt hinter dem letzten
     * erkannten Abschnitt fort.
     */
    VIDEO_TRANSCRIPT,
    /**
     * „Folien beschreiben“ für „In PPTX umwandeln“: fehlende Standbilder der Folienmarken ziehen und jede Folie mit
     * dem Vision-Modell beschreiben, parallel; nur Seiten mit KI-Freigabe (ADR 0028). Fortsetzen fragt nur Folien
     * ohne Beschreibung.
     */
    VIDEO_SLIDES,
    /**
     * „Transkript korrigieren“ für „In PPTX umwandeln“: das Text-Modell korrigiert Spracherkennungsfehler mit den
     * Folienbeschreibungen als Hilfe, paketweise parallel; wartet auf {@link #VIDEO_TRANSCRIPT} und
     * {@link #VIDEO_SLIDES}.
     */
    TRANSCRIPT_CORRECTION,
    /**
     * „Folienkommentare“ für „In PPTX umwandeln“: je Folie Standbild und Transkriptauszug einzeln an das
     * Vision-Modell, das den Vortragstext schreibt, parallel; wartet auf {@link #TRANSCRIPT_CORRECTION}. Fortsetzen
     * fragt nur Folien ohne Text.
     */
    SLIDE_NOTES,
    /**
     * „PPTX erzeugen“ für „In PPTX umwandeln“: die PowerPoint-Datei mit je Folie ihrem Standbild und dem Vortragstext
     * als Notiz im Ausgabeordner; wartet auf {@link #SLIDE_NOTES}.
     */
    PRESENTATION_EXPORT,
    /**
     * „Suchqualität messen“ (Q1): stellt die Fragen eines lokalen Testbestands über den Suchweg des Chats und schreibt
     * Ergebnis und Bericht in den Unterordner „Suchqualität“ des Ausgabeordners ({@code application.quality.SearchQualityRuns}). Quellenübergreifend
     * ({@link Jobs#ALL_SOURCES}); Liste, Statuszeile und Meldungen nennen nur den Testbestand und Zahlen. Fortsetzen
     * misst nur die Fragen, die im Ergebnis fehlen.
     */
    SEARCH_QUALITY,
    /**
     * „Podcast erzeugen“ (Angelo 21.11.2026): die Update-Zusammenfassung als lockeres Gespräch zweier Stimmen, erst der
     * Plan, dann Text und Audio Abschnitt für Abschnitt im Hintergrund ({@code application.podcast.UpdatePodcasts}).
     * Jede Stufe liegt sofort auf der Platte; Fortsetzen und jeder neue Lauf erzeugen nur, was fehlt.
     */
    UPDATE_PODCAST,
    /**
     * „Lese-Ansichten vorladen“ (Fundstelle 15, Abnahme #1080): beim Aufklappen eines Knotens die Lese-Ansichten der
     * Kinder, ein Klick ({@link Jobs#submitInteractive}), nie hinter Hintergrund ({@code application.reading.ReadingViews}).
     * Den Aufrufer hält nur der Speicher; nach einem Neustart wird nichts fortgesetzt, das Aufklappen lädt selbst.
     */
    READING_PREFETCH,
    /**
     * „Analysieren“ eines Dokuments (ADR 0035, Abschnitt 4), etwa aus der E-Akte: Fakten in drei Stufen (Regex, KI nur
     * mit Freigabe, Validierung), Teilergebnisse sofort; ein Klick ({@link Jobs#submitInteractive}). Ergebnisse liegen
     * nur im Speicher; nach einem Neustart wird nichts fortgesetzt
     * ({@code application.deadlines.DeadlineAnalysisService}).
     */
    DOCUMENT_ANALYSIS,
    /** Buchfunktion Phase 3: Research4j füllt einen Favoritenordner als Korb (Arbeitszweig, nur auf Klick). */
    RESEARCH,
    /**
     * Buchfunktion Phase 4: Writing4j schreibt aus einem Korb-Ordner das Buch, in zwei Aufträgen je Klick (Vorbereiten,
     * Schreiben); nur im Speicher, nach einem Neustart wird nichts fortgesetzt (Arbeitszweig).
     */
    BOOK,
    /**
     * Hintergrundauftrag (Angelo 21.11.2026, ADR 0025 Nachtrag „Hintergrundaufträge“): alles, was die App von sich aus
     * anstößt, etwa fehlende Seiten nachladen oder Bilder automatisch beschreiben. Welche Arbeit, sagt die Angabe
     * {@code background.kind}; zusammengehörige Unteraufträge (je Space einer) tragen dieselbe {@code background.group}
     * ({@code application.background.BackgroundJobs}). Nachrangig: startet erst, wenn kein anderer Auftrag wartet, und
     * belegt nie alle Plätze. Ein durch Beenden des Dienstes unterbrochener Hintergrundauftrag läuft nach dem Neustart
     * von selbst im selben Eintrag weiter; ein angehaltener bleibt angehalten.
     */
    BACKGROUND;

    /** Hintergrundauftrag ({@link #BACKGROUND}): nachrangig, eigener Reiter in der Auftragsliste. */
    public boolean background() {
        return this == BACKGROUND;
    }

    /**
     * Rechnet nur lokal am PC, ohne KI im Netz und ohne Quelle (Angelo 24.11.2026: „die Plätze beziehen sich ja auf die
     * KI-Ressourcen im Netz, nicht auf die lokalen Ressourcen am PC“): Aussagenanalyse mit dem MaltParser, „Neu lesen“,
     * Import eines Pakets, „Daten bereinigen“, Graph-Export, Umwandeln und „PPTX erzeugen“. Solche Aufträge laufen auf
     * eigener Spur ({@link Jobs#localSlots()}), belegen keinen Platz unter „Vorbereitung“ und halten keinen anderen
     * Auftrag auf.
     */
    public boolean local() {
        return this == STATEMENT_ANALYSIS || this == REREAD || this == IMPORT || this == PURGE
                || this == KNOWLEDGE_GRAPH_EXPORT || this == CONVERSION || this == PRESENTATION_EXPORT;
    }

    /** Gehört zu „In PPTX umwandeln“ (Angelo 20.11.2026). */
    public boolean presentation() {
        return this == VIDEO_TRANSCRIPT || this == VIDEO_SLIDES || this == TRANSCRIPT_CORRECTION
                || this == SLIDE_NOTES || this == PRESENTATION_EXPORT;
    }

    /**
     * Lädt von der Quelle herunter: Bilder, Anhänge und die Exporte aus der Quelle (Confluence-Metadaten, Analytics;
     * #944). Erst Vorbereitung (Seiten aus dem Bestand bestimmen, ohne Quelle), dann Download, mit je eigener Grenze
     * ({@link JobStage}): Vorbereitung {@code jobs.parallel-preparations}, Download {@code jobs.parallel-downloads},
     * in der App die Auswahl „Vorbereitung“ und „Downloads“ der Auftragsliste. Abgleiche und
     * Seitenexporte zählen getrennt, aber nach derselben Einstellung „Vorbereitung“. Die Verbindungen je Download („Verbindungen“) teilen nur Bilder und
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
     * „Daten bereinigen“ ({@link #PURGE}) löscht beim neuen Lauf von vorn; schon geleerte Ablagen bleiben leer
     * (Fundstelle 22). „Buch schreiben“ ({@link #BOOK}) bereitet im selben Eintrag neu vor; fertige Quellensteckbriefe
     * kommen aus dem Gedächtnis am Ordner (Angelo 27.11.2026).
     */
    public boolean resumable() {
        return this == SYNC || this == EXPORT || this == ASSETS || this == ATTACHMENTS || this == REREAD
                || this == UPDATE_SUMMARY || this == METADATA_EXPORT || this == ANALYTICS_EXPORT || presentation()
                || this == SEARCH_QUALITY || this == UPDATE_PODCAST || this == BACKGROUND || this == PURGE
                || this == BOOK;
    }

    /** Export aus der Quelle per REST-Abfrage (Metadaten, Analytics): Gescheitertes fragt ein neuer Lauf erneut. */
    public boolean sourceQuery() {
        return this == METADATA_EXPORT || this == ANALYTICS_EXPORT;
    }
}
