package com.aresstack.spaceharvester.deck.betaview;

import java.io.IOException;
import java.util.List;

/**
 * Port: eine angemeldete Verbindung zum BetaView-Webserver. Der Server merkt sich das zuletzt geöffnete Dokument;
 * Seiten, Download, Merken und Dokumentinfo gelten immer diesem. Alle Aufrufe blockieren; die Sitzung
 * ({@link BetaViewSession}) reiht sie nacheinander ein und sorgt dafür, dass das richtige Dokument offen ist.
 */
public interface BetaViewConnection {

    /** Sucht mit der Auswahl und liefert die Trefferliste ({@code select} → {@code result} → {@code showResult}). */
    ResultTable search(SearchFilter filter) throws IOException;

    /** Öffnet ein Dokument über seine Aktion aus Treffer, Reiter oder Lesezeichen. */
    OpenedDocument open(String action) throws IOException;

    /** Text der Seite {@code page} (ab 1) des zuletzt geöffneten Dokuments. */
    String page(int page) throws IOException;

    /** Was der Download-Dialog für das zuletzt geöffnete Dokument anbietet. */
    DownloadChoices downloadChoices() throws IOException;

    /** Lädt das zuletzt geöffnete Dokument herunter. */
    DownloadedFile download(DownloadOptions options) throws IOException;

    /** Die Dokumentinformationen (Eigenschaften) des zuletzt geöffneten Dokuments. */
    List<InfoLine> properties() throws IOException;

    /** Der Vorschlag des Servers für Name und Beschreibung, wenn man das zuletzt geöffnete Dokument merkt. */
    NameAndDescription bookmarkProposal() throws IOException;

    /** Merkt das zuletzt geöffnete Dokument (Lesezeichen des Servers). */
    void bookmark(NameAndDescription bookmark) throws IOException;

    /** Die gemerkten Dokumente. */
    List<Bookmark> bookmarks() throws IOException;

    /** Die gespeicherten Suchen. */
    List<SavedSearch> savedSearches() throws IOException;

    /** Führt eine gespeicherte Suche aus. */
    ResultTable run(SavedSearch search) throws IOException;

    /** Speichert die zuletzt ausgeführte Suche unter Name und Beschreibung. */
    void saveSearch(NameAndDescription search) throws IOException;

    /** Die Dokumente, die im Dokumentbrowser des Servers noch offen sind (etwa aus der letzten Sitzung). */
    List<DocumentTab> openTabs() throws IOException;

    /** Schließt einen Reiter des Dokumentbrowsers auf dem Server. */
    void close(DocumentTab document) throws IOException;

    /** Schließt alle Reiter des Dokumentbrowsers auf dem Server. */
    void closeAll() throws IOException;

    /** Die Notizen zum zuletzt geöffneten Dokument als Text ({@code document.notes.list.action}). */
    String notes() throws IOException;

    /** Die markierten Bereiche (Hold/Hide) des zuletzt geöffneten Dokuments ({@code holdhide.list.action}). */
    String markedRanges() throws IOException;

    /** Volltextsuche im zuletzt geöffneten Dokument ({@code document.search.action}); das Ergebnis als Text. */
    String searchText(String query) throws IOException;

    /** Die Druckinformationen des zuletzt geöffneten Dokuments ({@code printDialog.action}). */
    String printInfo() throws IOException;
}
