package com.aresstack.spaceharvester.application.jobs;

/**
 * Zustand eines Auftrags. Übergänge: {@code QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED} und
 * {@code QUEUED → CANCELLED}.
 *
 * <p>{@link #COMPLETED} heißt nur, dass der Auftrag fehlerfrei durchgelaufen ist. Ob daraus eine bestätigte
 * Cache-Generation oder ein vollständiges Artefakt wird, entscheidet der Auftrag selbst und hält es in seinem Ergebnis
 * fest; der Jobstatus allein behauptet das nie.
 */
public enum JobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
