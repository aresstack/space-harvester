package com.aresstack.spaceharvester.app.event;

/**
 * Stand des Auftrags für die Statuszeile, veröffentlicht auf dem {@link ApplicationEventBus} (Muster aus
 * MainframeMate). {@code jobId == null} heißt: kein Auftrag.
 *
 * @param text          Schritt bzw. Gegenstand, etwa „Download HANDBUCH · 40 von 127 Seiten“
 * @param done          erledigt (nur mit {@code total > 0})
 * @param total         Gesamtzahl; 0, wenn unbekannt
 * @param indeterminate läuft ohne bekannten Fortschritt
 * @param outcome       laufend oder der Endzustand
 */
public record JobProgressEvent(String jobId, String text, long done, long total, boolean indeterminate,
                               Outcome outcome, boolean cancelRequested)
        implements ApplicationEvent<JobProgressEvent> {

    /** Laufend oder wie der Auftrag endete. */
    public enum Outcome {
        NONE, RUNNING, COMPLETED, FAILED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    /** Kein Auftrag. */
    public static JobProgressEvent none() {
        return new JobProgressEvent(null, "Kein Auftrag läuft", 0, 0, false, Outcome.NONE, false);
    }

    @Override
    public JobProgressEvent getPayload() {
        return this;
    }
}
