package com.aresstack.spaceharvester.deck.ftp;

import com.aresstack.spaceharvester.deck.ftp.jes.JesConnection;
import java.io.IOException;

/**
 * Port: baut angemeldete FTP-Verbindungen auf (blockierend, nie auf dem EDT). Das Passwort gehört dem Aufrufer; der
 * Adapter behält keine Kopie.
 */
public interface FtpConnector {

    /** Verbindung für Datasets und Dateien. */
    FileConnection files(FtpProfile profile, String user, char[] password) throws IOException;

    /** Verbindung im JES-Modus für Jobs und Spool. */
    JesConnection jes(FtpProfile profile, String user, char[] password) throws IOException;
}
