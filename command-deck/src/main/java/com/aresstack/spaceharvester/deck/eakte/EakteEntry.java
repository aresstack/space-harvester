package com.aresstack.spaceharvester.deck.eakte;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein Eintrag einer E-Akte-Mappe, wie WebDAV ihn meldet.
 *
 * @param name        Anzeigename
 * @param href        Pfad auf dem Server, so wie WebDAV ihn liefert (prozentkodiert); damit wird er wieder geöffnet
 * @param folder      Mappe (WebDAV-Sammlung) statt Dokument
 * @param size        Größe in Byte, {@code -1} unbekannt
 * @param modified    letzte Änderung, {@code null} unbekannt
 * @param contentType MIME-Typ, leer wenn unbekannt
 */
public record EakteEntry(String name, String href, boolean folder, long size, Instant modified, String contentType) {

    public EakteEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(href, "href");
        contentType = contentType == null ? "" : contentType;
    }
}
