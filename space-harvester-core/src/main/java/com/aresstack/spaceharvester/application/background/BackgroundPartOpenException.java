package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;

/**
 * Fortsetzen abgelehnt, weil für Quelle und Space schon ein anderer Unterauftrag derselben Art wartet, angehalten ist
 * oder läuft ({@link BackgroundKind#exclusive()}, Abnahme #1052). Kein Fehler: Der offene Teil bestimmt seine Arbeit
 * selbst aus dem Bestand und erledigt sie mit; die Oberfläche zeigt das gelb als „läuft schon“ ({@link #REASON}).
 */
public class BackgroundPartOpenException extends JobNotResumableException {

    /** Der Grund in einem Satz, ohne Auftragskennung, wie ihn die Oberfläche unter „Läuft schon“ zeigt. */
    public static final String REASON = "Ein anderer Teil derselben Art für diese Quelle und diesen Space wartet, ist "
            + "angehalten oder läuft und erledigt die Arbeit mit.";

    /** Der offene Unterauftrag, der die Arbeit mit erledigt. */
    private final String openJobId;

    public BackgroundPartOpenException(String jobId, String openJobId) {
        super(jobId, "läuft schon. " + REASON);
        this.openJobId = openJobId;
    }

    public String openJobId() {
        return openJobId;
    }
}
