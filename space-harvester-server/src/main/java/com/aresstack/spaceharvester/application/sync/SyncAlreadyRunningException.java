package com.aresstack.spaceharvester.application.sync;

/** Für diesen Space wartet oder läuft bereits ein Sync-Auftrag; ein zweiter würde nur mit ihm kollidieren. */
public class SyncAlreadyRunningException extends RuntimeException {

    private final String jobId;

    public SyncAlreadyRunningException(String jobId) {
        super("Für diesen Space läuft bereits ein Abgleich");
        this.jobId = jobId;
    }

    /** ID des laufenden Auftrags. */
    public String jobId() {
        return jobId;
    }
}
