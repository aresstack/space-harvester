package com.aresstack.spaceharvester.application.sources;

/** Es gibt keine Quelle mit dieser ID in der Konfiguration. */
public class UnknownSourceException extends RuntimeException {

    private final String sourceId;

    public UnknownSourceException(String sourceId) {
        super("Keine Quelle mit der ID '" + sourceId + "' konfiguriert");
        this.sourceId = sourceId;
    }

    public String sourceId() {
        return sourceId;
    }
}
