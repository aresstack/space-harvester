package com.aresstack.spaceharvester.application.simplification;

import java.util.Objects;

/** Die Vorstufe kann für diese Seite nicht laufen (ADR 0032, FL3); die Meldung nennt nie Inhalte. */
public final class SimplificationUnavailableException extends RuntimeException {

    /** Warum. */
    public enum Reason {
        /** Kein Modell für den Schritt eingestellt. */
        NO_MODEL,
        /** Die Seite ist nicht (mehr) für den KI-Dienst freigegeben (ADR 0028). */
        NOT_RELEASED
    }

    private final Reason reason;

    public SimplificationUnavailableException(Reason reason) {
        super(switch (Objects.requireNonNull(reason, "reason")) {
            case NO_MODEL -> "Kein Modell für die Vorstufe eingestellt";
            case NOT_RELEASED -> "Seite nicht für den KI-Dienst freigegeben";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
