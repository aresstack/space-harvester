package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.io.IOException;
import java.util.List;

/**
 * Angemeldete FTP-Sitzung, wie sie die Quelle braucht: Kinder eines Pfads auflisten und eine Datei als Text lesen.
 * Pfade sind im MVS-Modus Datasetnamen ohne Anführungszeichen ({@code HLQ.COBOL.SRC(PGM1)}), sonst absolute
 * Unix-Pfade.
 *
 * <p>Schnitt nach corenth ({@code holkas.ftp.FtpClientSession}); die Implementierung kommt aus MainframeMate.
 */
interface FtpClientSession extends FtpSessionPool.Session {

    /** {@code true}, wenn der Server sich als MVS/z/OS meldet. */
    boolean mvs();

    /** Die direkten Kinder; leer, wenn {@code path} keine Kinder hat oder eine Datei ist. */
    List<FtpEntry> list(String path) throws IOException;

    /** Inhalt als Text (Satzstruktur, Füllbytes und Zeichensatz aufgelöst). */
    String readText(String path) throws IOException;

    /** Trennt die Verbindung; mehrfacher Aufruf ist erlaubt. */
    @Override
    void close();

    /** Eintrag einer Auflistung. */
    final class FtpEntry {

        private final String name;
        private final String path;
        private final boolean directory;
        private final long modifiedMillis;

        FtpEntry(String name, String path, boolean directory, long modifiedMillis) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.modifiedMillis = modifiedMillis;
        }

        String name() {
            return name;
        }

        String path() {
            return path;
        }

        boolean directory() {
            return directory;
        }

        /** {@code 0}, wenn der Server keine Zeit liefert. */
        long modifiedMillis() {
            return modifiedMillis;
        }
    }
}
