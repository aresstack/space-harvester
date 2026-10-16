package com.aresstack.spaceharvester.application.sync;

/**
 * Aus diesem Lauf darf keine Generation entstehen (Inventur abgebrochen oder widersprüchlich, oder eine angeblich
 * fehlende Seite ist doch im Space). Die bisherige Generation bleibt gültig; der Lauf kann neu beginnen.
 */
public class UnconfirmableSyncException extends RuntimeException {

    public UnconfirmableSyncException(String message) {
        super(message);
    }
}
