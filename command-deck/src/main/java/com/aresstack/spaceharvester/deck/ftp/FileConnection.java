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

    /** List only PDS members for copybook resolution, without traversing sub-datasets. */
    default List<RemoteEntry> listCopybookMembers(String dataset) throws IOException {
        return list(dataset);
    }

    /**
     * Listet mit Details (auf MVS RECFM, LRECL, Dsorg, Datum, Zeilen aus {@code LIST}), wie die Detailansicht von
     * MainframeMate. Ohne Details gleich {@link #list(String)}.
     */
    default List<RemoteEntry> list(String path, boolean details) throws IOException {
        return list(path);
    }

    /** Liest Text; auf MVS mit Satzstruktur werden die Satzenden zu Zeilenumbrüchen. */
    /**
     * Ob ein Doppelklick den Eintrag wie einen Ordner öffnet (Liste) statt im Editor. Auf dem Mainframe wie
     * MainframeMate ({@code MvsBrowserController.openResource}): HLQ und Qualifier immer, ein Dataset nur, wenn es
     * partitioniert ist; Member nie. Sonst: ob es ein Ordner ist.
     */
    default boolean opensAsFolder(RemoteEntry entry) throws IOException {
        return entry.directory();
    }

    RemoteFile read(String path) throws IOException;

    /** Liest Bytes ohne Umwandlung. */
    byte[] readBinary(String path) throws IOException;

    /** Capture a bounded prefix without text conversion; report size and hash of the completed transfer. */
    default BinarySample readBinarySample(String path, int limit) throws IOException {
        throw new IOException("Binärdiagnose wird von dieser Verbindung nicht unterstützt.");
    }

    /** Stream original transfer bytes to the caller-owned output without closing it. */
    default void downloadBinary(String path, java.io.OutputStream output) throws IOException {
        throw new IOException("Binärdownload wird von dieser Verbindung nicht unterstützt.");
    }

    /** Read the DCB metadata if the server exposes it. */
    default RemoteEntry.Details datasetDetails(String path) throws IOException {
        return RemoteEntry.Details.NONE;
    }

    /** Schreibt Text; auf MVS mit Satzstruktur wird jede Zeile ein Satz. */
    void write(String path, String text) throws IOException;

    void writeBinary(String path, byte[] content) throws IOException;

    /** Löscht Datei, Member oder Dataset (leerer Ordner). */
    void delete(String path) throws IOException;

    /** Legt auf MVS ein Dataset an, sonst einen Ordner. */
    void createDirectory(String path) throws IOException;

    /**
     * Legt auf MVS ein Dataset mit Anlageparametern an (PO über {@code MKD}, PS über leeres {@code STOR}); ohne MVS
     * einen Ordner.
     */
    default void createDataset(String path, DatasetAttributes attributes) throws IOException {
        createDirectory(path);
    }

    /**
     * Schreibt nur, wenn der Stand auf dem Host noch {@code expectedHash} hat; ohne erwartete Prüfsumme immer.
     *
     * @throws WriteConflictException wenn inzwischen geändert wurde
     */
    default RemoteFile writeIfUnchanged(String path, String text, String expectedHash) throws IOException {
        if (expectedHash != null && !expectedHash.isEmpty()) {
            RemoteFile current = read(path);
            if (!current.writeRestriction().isEmpty()) throw new IOException(current.writeRestriction());
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
