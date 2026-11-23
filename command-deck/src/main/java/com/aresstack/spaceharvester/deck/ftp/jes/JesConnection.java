package com.aresstack.spaceharvester.deck.ftp.jes;

import com.aresstack.spaceharvester.deck.ftp.FtpReplyException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Port: eine FTP-Verbindung im JES-Modus ({@code SITE FILETYPE=JES}). Getrennt von der Dataset-Verbindung, weil der
 * Modus für die ganze Verbindung gilt (wie in MainframeMate). Nicht threadsicher.
 */
public interface JesConnection extends AutoCloseable {

    /** Der angemeldete Benutzer (Vorgabe für den Besitzerfilter). */
    String user();

    List<JesJob> jobs(JobFilter filter) throws IOException;

    /** Spool-Dateien laut Host; leer, wenn er keine Liste liefert. */
    List<SpoolFile> spoolFiles(String jobId) throws IOException;

    String spool(String jobId, int spoolId) throws IOException;

    /**
     * Ruft eine Spool-Datei einzeln ab und erkennt ihren DD-Namen aus der Antwort des Hosts oder dem Inhalt
     * (Verfahren „PROBE“ aus MainframeMate). Leer, wenn es sie nicht gibt.
     */
    default Optional<SpoolFile> probe(String jobId, int spoolId) throws IOException {
        try {
            return Optional.of(JesListing.probed(spoolId, "", spool(jobId, spoolId)));
        } catch (FtpReplyException missing) {
            return Optional.empty();
        }
    }

    /** Die gesamte Ausgabe am Stück ({@code jobId.x}). */
    String allSpool(String jobId) throws IOException;

    /** Löscht die Ausgabe des Jobs aus dem Spool. */
    void delete(String jobId) throws IOException;

    /** Reicht JCL als Job ein. */
    SubmittedJob submit(String jcl) throws IOException;

    boolean alive();

    @Override
    void close();
}
