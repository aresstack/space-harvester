package com.aresstack.spaceharvester.application.jobs;

/**
 * Der Auftrag lässt sich nicht anhalten oder nicht im selben Eintrag fortsetzen (ADR 0025): eine Art ohne Fortsetzen,
 * ein Zustand, aus dem es kein Fortsetzen gibt, oder ein Auftrag aus der Zeit vor dem letzten Neustart des Dienstes.
 * Ein neuer Start mit denselben Angaben holt dann ebenfalls nur, was noch fehlt.
 */
public class JobNotResumableException extends RuntimeException {

    public JobNotResumableException(String jobId, String reason) {
        super("Auftrag " + jobId + " nicht fortsetzbar: " + reason);
    }
}
