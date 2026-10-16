package com.aresstack.spaceharvester.application.query;

/**
 * Eine Aktualitätsprüfung gegen die Quelle ({@code refresh=true}) ist angefordert, aber nicht möglich. Dann wird nie
 * stattdessen der Cache-Stand ausgeliefert.
 */
public class RefreshUnavailableException extends RuntimeException {

    public RefreshUnavailableException(String message) {
        super(message);
    }
}
