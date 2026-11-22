package com.aresstack.spaceharvester.deck.ftp;

import java.io.IOException;

/**
 * Der Host hat geantwortet, aber abgelehnt (etwa „Datei nicht gefunden“, keine Berechtigung). Die Verbindung steht
 * noch; anders als bei einem Verbindungsfehler wird nicht neu verbunden.
 */
public class FtpReplyException extends IOException {

    private final int code;

    public FtpReplyException(String message, int code) {
        super(message);
        this.code = code;
    }

    /** Der FTP-Antwortcode, 0 wenn keiner. */
    public int code() {
        return code;
    }
}
