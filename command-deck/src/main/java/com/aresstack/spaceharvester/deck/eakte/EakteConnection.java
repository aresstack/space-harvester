package com.aresstack.spaceharvester.deck.eakte;

import java.io.IOException;
import java.util.List;

/**
 * Eine angemeldete Verbindung zur E-Akte, nur lesend: Es gibt nur {@code PROPFIND Depth: 1} und {@code GET}, keine
 * schreibende WebDAV-Methode. Alle Methoden blockieren, also nie auf dem EDT. Läuft die Sitzung ab,
 * meldet sie sich ohne Kennwort über die bestehende Anmeldung neu an; braucht das ein Kennwort, wirft sie
 * {@link SessionExpiredException} statt es selbst zu senden.
 */
public interface EakteConnection extends AutoCloseable {

    /** Kurzname für Reiter und Statuszeile. */
    String label();

    /** Pfad der WebDAV-Wurzel (prozentkodiert), Ausgangspunkt für {@link #list(String)}. */
    String rootHref();

    /** Inhalt einer Mappe, Mappen zuerst, dann nach Namen. */
    List<EakteEntry> list(String href) throws IOException;

    /** Inhalt eines Dokuments im Speicher (für kleine Dokumente und Tests). */
    default byte[] read(String href) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        download(href, bytes);
        return bytes.toByteArray();
    }

    /**
     * Schreibt den Inhalt eines Dokuments gestreamt nach {@code target} (per {@code GET}, mit derselben Sitzung wie
     * {@link #list}). Liefert die Zahl der geschriebenen Bytes.
     */
    long download(String href, java.io.OutputStream target) throws IOException;

    @Override
    void close();
}
