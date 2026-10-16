package com.aresstack.spaceharvester.application.query;

/**
 * Das Angefragte liegt nicht im Cache, oder der Aufrufer darf es nicht sehen; beides ist nach außen nicht
 * unterscheidbar.
 */
public class CacheMissException extends RuntimeException {

    private final String sourceId;

    public CacheMissException(String sourceId, String message) {
        super(message);
        this.sourceId = sourceId;
    }

    public String sourceId() {
        return sourceId;
    }
}
