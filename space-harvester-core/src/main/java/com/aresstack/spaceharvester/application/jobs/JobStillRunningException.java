package com.aresstack.spaceharvester.application.jobs;

/** Der Auftrag wartet oder läuft noch; löschen lässt sich nur ein beendeter Auftrag (vorher abbrechen). */
public class JobStillRunningException extends RuntimeException {

    public JobStillRunningException(String jobId) {
        super("Auftrag läuft noch: " + jobId);
    }
}
