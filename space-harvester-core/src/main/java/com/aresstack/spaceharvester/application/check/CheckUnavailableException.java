package com.aresstack.spaceharvester.application.check;

import java.util.Objects;

/** Der Prüfschritt kann für diese Seite nicht laufen (ADR 0032, FL4); die Meldung nennt nie Inhalte. */
public final class CheckUnavailableException extends RuntimeException {

    /** Warum. */
    public enum Reason {
        /** Kein Modell für den Schritt eingestellt. */
        NO_MODEL,
        /** Die Seite ist nicht (mehr) für den KI-Dienst freigegeben (ADR 0028). */
        NOT_RELEASED,
        /** Die gespeicherte Analyse fehlt oder ist veraltet; erst neu analysieren. */
        NOT_CURRENT
    }

    private final Reason reason;

    public CheckUnavailableException(Reason reason) {
        super(switch (Objects.requireNonNull(reason, "reason")) {
            case NO_MODEL -> "Kein Modell für den Prüfschritt eingestellt";
            case NOT_RELEASED -> "Seite nicht für den KI-Dienst freigegeben";
            case NOT_CURRENT -> "Keine aktuelle Aussagenanalyse der Seite";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
