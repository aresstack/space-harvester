package com.aresstack.spaceharvester.application.glossary;

import java.util.Objects;

/** Der Glossarlauf kann nicht starten: kein Chat-Modell eingestellt oder keine Quelle im Umfang freigegeben. */
public class GlossaryUnavailableException extends RuntimeException {

    public enum Reason {
        NO_MODEL,
        NOT_RELEASED
    }

    private final Reason reason;
    private final String sourceId;

    public GlossaryUnavailableException(Reason reason, String sourceId) {
        super(reason == Reason.NO_MODEL ? "Kein Chat-Modell eingestellt"
                : sourceId == null ? "Keine Quelle ist für die Verarbeitung durch den KI-Dienst freigegeben"
                : "Quelle " + sourceId + " ist nicht für die Verarbeitung durch den KI-Dienst freigegeben");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.sourceId = sourceId;
    }

    public Reason reason() {
        return reason;
    }

    /** Die gesperrte Quelle oder {@code null}, wenn der Umfang alle Quellen war. */
    public String sourceId() {
        return sourceId;
    }
}
