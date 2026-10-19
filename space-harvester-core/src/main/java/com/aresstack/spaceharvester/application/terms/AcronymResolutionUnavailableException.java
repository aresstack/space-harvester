package com.aresstack.spaceharvester.application.terms;

import java.util.Objects;

/** Die KI-Akronymauflösung (#122) kann für diese Quelle nicht laufen; die Meldung nennt keine Adresse und keinen Key. */
public class AcronymResolutionUnavailableException extends RuntimeException {

    /** Warum nicht. */
    public enum Reason {
        /** Kein KI-Dienst oder kein Chat-Modell eingestellt. */
        NO_MODEL,
        /** Die Quelle steht nicht in {@code external-processing-sources}; ihr Text geht nie an den Dienst. */
        NOT_RELEASED
    }

    private final Reason reason;
    private final String sourceId;

    public AcronymResolutionUnavailableException(Reason reason, String sourceId) {
        super(reason == Reason.NO_MODEL ? "Kein Chat-Modell eingestellt"
                : "Quelle " + sourceId + " ist nicht für die Verarbeitung durch den KI-Dienst freigegeben");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.sourceId = sourceId;
    }

    public Reason reason() {
        return reason;
    }

    public String sourceId() {
        return sourceId;
    }
}
