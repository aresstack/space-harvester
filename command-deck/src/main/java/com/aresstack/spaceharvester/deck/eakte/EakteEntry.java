package com.aresstack.spaceharvester.deck.eakte;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein Eintrag einer E-Akte-Mappe, wie WebDAV ihn meldet. Fehlt eine Eigenschaft in der Antwort, bleibt sie leer
 * ({@code -1}, {@code null} oder {@code ""}); erfunden wird nichts.
 *
 * @param name          Anzeigename ({@code DAV:displayname}, sonst der letzte Abschnitt von {@code href})
 * @param href          Kennung auf dem Server, so wie WebDAV sie liefert (prozentkodiert); damit wird er geöffnet,
 *                      nie über den angezeigten Namen
 * @param folder        Mappe (WebDAV-Sammlung) statt Dokument
 * @param size          Größe in Byte, {@code -1} unbekannt
 * @param modified      letzte Änderung, {@code null} unbekannt
 * @param contentType   MIME-Typ, leer wenn unbekannt
 * @param created       Erstellung ({@code DAV:creationdate}), {@code null} unbekannt
 * @param etag          Änderungskennung ({@code DAV:getetag}), leer wenn unbekannt
 * @param objectAddress Fabasoft-Objektadresse ({@code objaddress}, etwa {@code COO.1.2.3}), leer wenn unbekannt
 */
public record EakteEntry(String name, String href, boolean folder, long size, Instant modified, String contentType,
                         Instant created, String etag, String objectAddress) {

    public EakteEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(href, "href");
        contentType = contentType == null ? "" : contentType;
        etag = etag == null ? "" : etag;
        objectAddress = objectAddress == null ? "" : objectAddress;
    }

    /** Ohne die selteneren Eigenschaften. */
    public EakteEntry(String name, String href, boolean folder, long size, Instant modified, String contentType) {
        this(name, href, folder, size, modified, contentType, null, "", "");
    }
}
