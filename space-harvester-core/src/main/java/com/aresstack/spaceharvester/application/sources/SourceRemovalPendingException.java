package com.aresstack.spaceharvester.application.sources;

/**
 * Eine Quelle dieser ID wird noch entfernt: Der Auftrag, der ihre Daten löscht, wartet, läuft oder setzt nach einem
 * Neustart fort (Fundstelle 22). Bis er endet, lässt sich dieselbe ID nicht neu anlegen.
 */
public class SourceRemovalPendingException extends RuntimeException {

    private final String sourceId;
    private final String jobId;

    public SourceRemovalPendingException(String sourceId, String jobId) {
        super("Die Quelle " + sourceId + " wird noch entfernt");
        this.sourceId = sourceId;
        this.jobId = jobId;
    }

    public String sourceId() {
        return sourceId;
    }

    /** Der Auftrag, der ihre Daten löscht. */
    public String jobId() {
        return jobId;
    }
}
