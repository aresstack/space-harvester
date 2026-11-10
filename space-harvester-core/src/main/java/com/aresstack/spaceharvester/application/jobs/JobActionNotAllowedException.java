package com.aresstack.spaceharvester.application.jobs;

/** Die Aktion ist im wirksamen Zustand des Auftrags nicht erlaubt ({@link JobLifecycleState#allows}). */
public class JobActionNotAllowedException extends RuntimeException {

    private final String jobId;
    private final JobAction action;
    private final String state;

    public JobActionNotAllowedException(String jobId, JobAction action, JobLifecycleState state) {
        super("Aktion " + action + " ist im Zustand " + state.key() + " nicht erlaubt");
        this.jobId = jobId;
        this.action = action;
        this.state = state.key();
    }

    public String jobId() {
        return jobId;
    }

    public JobAction action() {
        return action;
    }

    /** Schlüssel des Zustands ({@link JobLifecycleState#key()}). */
    public String state() {
        return state;
    }
}
