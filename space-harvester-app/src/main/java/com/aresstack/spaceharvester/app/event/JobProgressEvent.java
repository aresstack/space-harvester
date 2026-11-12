package com.aresstack.spaceharvester.app.event;

/**
 * Gesamtstand aller Aufträge für die Statuszeile (Angelo 11.11.2026), veröffentlicht auf dem
 * {@link ApplicationEventBus} (Muster aus MainframeMate). Anhalten und Abbrechen stehen nicht mehr in der Statuszeile,
 * sondern für alle Aufträge zugleich in der Kopfzeile der Aufträge.
 *
 * @param text          Stand in Worten, etwa „Aufträge: 3 laufen, 2 warten · 41 % · noch ≈ 12 min“
 * @param done          erledigt (nur mit {@code total > 0})
 * @param total         Gesamtzahl; 0, wenn unbekannt
 * @param indeterminate läuft ohne bekannten Fortschritt
 * @param outcome       laufend, angehalten oder wie der Durchgang endete
 */
public record JobProgressEvent(String text, long done, long total, boolean indeterminate, Outcome outcome)
        implements ApplicationEvent<JobProgressEvent> {

    /** Laufend, angehalten oder wie der Durchgang endete. */
    public enum Outcome {
        NONE, RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == PAUSED;
        }
    }

    /** Kein Auftrag. */
    public static JobProgressEvent none() {
        return new JobProgressEvent("Kein Auftrag läuft", 0, 0, false, Outcome.NONE);
    }

    @Override
    public JobProgressEvent getPayload() {
        return this;
    }
}
