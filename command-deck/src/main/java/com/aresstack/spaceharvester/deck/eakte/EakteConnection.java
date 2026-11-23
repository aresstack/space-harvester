package com.aresstack.spaceharvester.deck.eakte;

import java.io.IOException;
import java.util.List;

/**
 * Eine angemeldete Verbindung zur E-Akte. Alle Methoden blockieren, also nie auf dem EDT. Läuft die Sitzung ab,
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

    /** Inhalt eines Dokuments. */
    byte[] read(String href) throws IOException;

    @Override
    void close();
}
