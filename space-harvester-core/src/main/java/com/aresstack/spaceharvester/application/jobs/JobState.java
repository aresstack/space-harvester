package com.aresstack.spaceharvester.application.jobs;

/**
 * Zustand eines Auftrags. Übergänge: {@code QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED | PAUSED},
 * {@code QUEUED → CANCELLED | PAUSED} und, nur über {@link Jobs#resume(String, String)},
 * {@code PAUSED | FAILED | CANCELLED → QUEUED} (ADR 0025).
 *
 * <p>{@link #COMPLETED} heißt nur, dass der Auftrag fehlerfrei durchgelaufen ist. Ob daraus eine bestätigte
 * Cache-Generation oder ein vollständiges Artefakt wird, entscheidet der Auftrag selbst und hält es in seinem Ergebnis
 * fest; der Jobstatus allein behauptet das nie.
 *
 * <p>{@link #PAUSED} heißt: der Benutzer hat angehalten. Der Auftrag läuft nicht mehr und belegt keinen Platz, das
 * bisher Geladene bleibt, und Fortsetzen holt nur, was noch fehlt. Er ist weder abgebrochen noch gescheitert.
 */
public enum JobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED;

    /** Läuft nicht mehr und wartet nicht; ein angehaltener Auftrag zählt dazu, auch wenn er fortgesetzt werden kann. */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == PAUSED;
    }
}
