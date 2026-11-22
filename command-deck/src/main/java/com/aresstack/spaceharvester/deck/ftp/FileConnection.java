package com.aresstack.spaceharvester.deck.ftp;

import java.io.IOException;
import java.util.List;

/**
 * Port: eine angemeldete FTP-Verbindung für Datasets, Members und Dateien. Nicht threadsicher; die Sitzung
 * ({@link FtpSession}) ruft sie immer nur von einem Thread zur Zeit. Verbindungsfehler sind {@link IOException},
 * Ablehnungen des Hosts {@link FtpReplyException}.
 */
public interface FileConnection extends AutoCloseable {

    /** Ob der Host ein MVS/z/OS ist (Datasets statt Ordner). */
    boolean mvs();

    /** Antwort auf SYST, leer wenn keine. */
    String systemType();

    /** Wo die Liste beginnt: auf MVS der HLQ des Benutzers, sonst das Arbeitsverzeichnis. */
    String home();

    List<RemoteEntry> list(String path) throws IOException;

    /** Liest Text; auf MVS mit Satzstruktur werden die Satzenden zu Zeilenumbrüchen. */
    RemoteFile read(String path) throws IOException;

    /** Liest Bytes ohne Umwandlung. */
    byte[] readBinary(String path) throws IOException;

    /** Schreibt Text; auf MVS mit Satzstruktur wird jede Zeile ein Satz. */
    void write(String path, String text) throws IOException;

    void writeBinary(String path, byte[] content) throws IOException;

    /** Löscht Datei, Member oder Dataset (leerer Ordner). */
    void delete(String path) throws IOException;

    /** Legt auf MVS ein Dataset an, sonst einen Ordner. */
    void createDirectory(String path) throws IOException;

    /**
     * Schreibt nur, wenn der Stand auf dem Host noch {@code expectedHash} hat; ohne erwartete Prüfsumme immer.
     *
     * @throws WriteConflictException wenn inzwischen geändert wurde
     */
    default RemoteFile writeIfUnchanged(String path, String text, String expectedHash) throws IOException {
        if (expectedHash != null && !expectedHash.isEmpty()) {
            RemoteFile current = read(path);
            if (!current.hash().equals(expectedHash)) {
                throw new WriteConflictException(current);
            }
        }
        write(path, text);
        return RemoteFile.of(path, text);
    }

    /** Ob die Steuerverbindung noch steht. */
    boolean alive();

    /** Meldet ab und trennt; wirft nie. */
    @Override
    void close();
}
