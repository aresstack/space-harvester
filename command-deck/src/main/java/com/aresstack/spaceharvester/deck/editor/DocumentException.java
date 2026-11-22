package com.aresstack.spaceharvester.deck.editor;

/** Laden oder Speichern ist gescheitert; die Meldung nennt den Grund für Menschen. */
public class DocumentException extends Exception {

    public DocumentException(String message) {
        super(message);
    }

    public DocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
