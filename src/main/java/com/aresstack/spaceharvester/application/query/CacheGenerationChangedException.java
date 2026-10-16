package com.aresstack.spaceharvester.application.query;

/** Die Cache-Generation, auf die sich ein Cursor oder eine laufende Abfrage bezieht, ist nicht mehr lesbar. */
public class CacheGenerationChangedException extends RuntimeException {

    public CacheGenerationChangedException(String message) {
        super(message);
    }
}
