package com.aresstack.spaceharvester.application.jobs;

/** Es gibt keinen Auftrag mit dieser ID. */
public class UnknownJobException extends RuntimeException {

    public UnknownJobException(String jobId) {
        super("Unbekannter Auftrag: " + jobId);
    }
}
