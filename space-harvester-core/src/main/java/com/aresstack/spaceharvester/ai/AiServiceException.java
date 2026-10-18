package com.aresstack.spaceharvester.ai;

import java.util.Objects;

/**
 * Der KI-Dienst hat nicht wie erwartet geantwortet. Die Meldung nennt weder API-Key noch Texte, die an den Dienst
 * gingen.
 */
public class AiServiceException extends RuntimeException {

    /** Was schiefging. */
    public enum Reason {
        /** Kein Dienst oder kein Modell eingerichtet. */
        NOT_CONFIGURED,
        /** Der Dienst lehnt den API-Key ab (401/403). */
        UNAUTHORIZED,
        /** Keine Verbindung, keine Proxy-Route oder Dienst überlastet. */
        UNREACHABLE,
        /** Frist überschritten. */
        TIMEOUT,
        /** Antwort unbrauchbar, etwa falsches Format oder falsche Vektordimension. */
        INVALID_RESPONSE
    }

    private final Reason reason;

    public AiServiceException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
