package com.aresstack.spaceharvester.application.presentation;

import java.util.Objects;

/** „In PPTX umwandeln“ kann für dieses Video nicht starten; der Grund sagt, was fehlt. */
public final class PresentationUnavailableException extends RuntimeException {

    /** Was fehlt. */
    public enum Reason {
        /** Kein Vision-Modell eingestellt. */
        NO_VISION_MODEL,
        /** Kein Chat-Modell (Text-Modell) eingestellt. */
        NO_TEXT_MODEL,
        /** Die Seite darf nicht an den KI-Dienst (ADR 0028). */
        NOT_RELEASED,
        /** Das Video hat keine Folienmarken. */
        NO_SLIDES
    }

    private final Reason reason;

    public PresentationUnavailableException(Reason reason) {
        super(message(Objects.requireNonNull(reason, "reason")));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case NO_VISION_MODEL -> "Für „In PPTX umwandeln“ ist kein Vision-Modell eingestellt.";
            case NO_TEXT_MODEL -> "Für „In PPTX umwandeln“ ist kein Text-Modell (Chat-Modell) eingestellt.";
            case NOT_RELEASED -> "Diese Seite ist nicht für den KI-Dienst freigegeben.";
            case NO_SLIDES -> "Das Video hat noch keine Folienmarken.";
        };
    }
}
