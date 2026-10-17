package com.aresstack.spaceharvester.application.sources;

/**
 * Ein Anhang ist größer als die Grenze des Aufrufers; der Download wurde abgebrochen, nichts wird gekürzt (#19). Als
 * {@link SourceAccessException} mit {@code INVALID_RESPONSE}, damit Aufrufer ohne eigene Behandlung wie bisher scheitern.
 */
public class AttachmentTooLargeException extends SourceAccessException {

    private final long maxBytes;

    public AttachmentTooLargeException(String sourceId, long maxBytes) {
        super(sourceId, Reason.INVALID_RESPONSE, "Anhang über der Grenze von " + maxBytes + " Bytes", null);
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
