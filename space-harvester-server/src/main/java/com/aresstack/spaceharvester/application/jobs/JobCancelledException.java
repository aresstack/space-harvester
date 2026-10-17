package com.aresstack.spaceharvester.application.jobs;

/** Der Auftrag hat einen angeforderten Abbruch bemerkt; wirft {@link JobContext#checkpoint()}. */
public class JobCancelledException extends RuntimeException {

    public JobCancelledException(String jobId) {
        super("Auftrag " + jobId + " wurde abgebrochen", null, false, false);
    }
}
