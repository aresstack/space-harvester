package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;

/**
 * „Nur Fehler neu versuchen“ (↻) an einem Hintergrundteil, der keine gescheiterte Arbeit mehr hat
 * ({@link BackgroundJobs#retryFailed}): kein Fehler, die Oberfläche meldet es gelb.
 */
public class NothingToRetryException extends JobNotResumableException {

    /** Was die Oberfläche zeigt. */
    public static final String REASON = "nichts zu wiederholen";

    public NothingToRetryException(String jobId) {
        super(jobId, REASON);
    }
}
