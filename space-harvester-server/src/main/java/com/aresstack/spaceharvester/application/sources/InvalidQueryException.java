package com.aresstack.spaceharvester.application.sources;

/** Eine Anfrage mit ungültigen Parametern, etwa einer Stapelgröße außerhalb des erlaubten Bereichs. */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
