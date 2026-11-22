package com.aresstack.spaceharvester.deck.files;

/** Ein Dateizugriff ist gescheitert; die Meldung ist für Menschen geschrieben und nennt den Grund. */
public class FileAccessException extends Exception {

    public FileAccessException(String message) {
        super(message);
    }

    public FileAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
