package com.aresstack.spaceharvester.deck.betaview;

import java.util.List;

/**
 * Was der Server beim Öffnen eines Dokuments zurückgibt: das Dokument selbst (Kennung, Seitenzahl, Kurzinfo) und alle
 * Reiter, die im Dokumentbrowser des Servers gerade offen sind.
 *
 * @param document  das geöffnete Dokument
 * @param tabs      alle offenen Reiter, das geöffnete darunter
 * @param pageCount Seitenzahl, mindestens 1
 * @param headline  Überschrift der Kurzinfo, leer ohne
 * @param info      Kurzinfo (Dokument-ID, Seiten und was der Server sonst nennt)
 */
public record OpenedDocument(DocumentTab document, List<DocumentTab> tabs, int pageCount, String headline,
                             List<InfoLine> info) {

    public OpenedDocument {
        tabs = List.copyOf(tabs);
        pageCount = Math.max(1, pageCount);
        headline = headline == null ? "" : headline.trim();
        info = List.copyOf(info);
    }
}
