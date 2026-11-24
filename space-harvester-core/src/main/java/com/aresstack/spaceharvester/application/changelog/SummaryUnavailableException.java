package com.aresstack.spaceharvester.application.changelog;

/** Eine Zusammenfassung lässt sich nicht starten (ADR 0030, Abschnitt 5). */
public class SummaryUnavailableException extends RuntimeException {

    public enum Reason {
        /** Kein Chat-Modell eingestellt. */
        NO_MODEL,
        /** Die Erstbefüllung hat keine Seitenliste und wird nicht zusammengefasst. */
        INITIAL_LOAD,
        /** Der Bereich hat keine Seite mit Inhalt; es gibt nichts zusammenzufassen (ADR 0034 §7). */
        EMPTY_AREA
    }

    private final Reason reason;

    public SummaryUnavailableException(Reason reason, String sourceId) {
        super(switch (reason) {
            case NO_MODEL -> "Kein Chat-Modell eingestellt";
            case INITIAL_LOAD -> "Die Erstbefüllung von " + sourceId + " wird nicht zusammengefasst";
            case EMPTY_AREA -> "Der Bereich hat noch keine Seite, die sich zusammenfassen lässt";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
