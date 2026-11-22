package com.aresstack.spaceharvester.deck.betaview;

/**
 * Ein auf dem Server geöffnetes Dokument (Reiter des Dokumentbrowsers von BetaView), wie {@code DocumentTab} in
 * MainframeMate.
 *
 * @param docId      Dokumentkennung
 * @param favId      Favorit, aus dem es stammt
 * @param linkId     Kennung zum Schließen ({@code closeSingleDocument.action?linkID=…})
 * @param title      Titel
 * @param timestamp  Zeitstempel, wie der Server ihn zeigt
 * @param openAction Aktion zum (erneuten) Öffnen, etwa {@code opendocumentlink.action?docid=…&favid=…}
 * @param active     ob der Server es gerade anzeigt
 */
public record DocumentTab(String docId, String favId, String linkId, String title, String timestamp,
                          String openAction, boolean active) {

    public DocumentTab {
        docId = clean(docId);
        favId = clean(favId);
        linkId = clean(linkId);
        title = clean(title);
        timestamp = clean(timestamp);
        openAction = clean(openAction);
    }

    /** Eindeutiger Schlüssel: die Link-Kennung, sonst die Öffnen-Aktion. */
    public String key() {
        return linkId.isEmpty() ? openAction : linkId;
    }

    /** Dieselbe Angabe als aktiv bzw. nicht aktiv. */
    public DocumentTab active(boolean value) {
        return new DocumentTab(docId, favId, linkId, title, timestamp, openAction, value);
    }

    /** Kurzname für Reiter: der Titel, sonst „Dokument“. */
    public String label() {
        return title.isEmpty() ? "Dokument" : title;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
