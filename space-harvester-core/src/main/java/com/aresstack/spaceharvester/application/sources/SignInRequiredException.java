package com.aresstack.spaceharvester.application.sources;

import java.util.Objects;

/**
 * Die Quelle liefert eine Datei erst nach einer zusätzlichen Anmeldung (Einmalcode, ADR 0018). Grund
 * {@link Reason#ACCESS_DENIED}, damit Aufrufer ohne Anmeldung wie bisher „keine Berechtigung“ sehen; wer anmelden kann,
 * nimmt die {@link #challenge()} und lädt nach der Freischaltung neu.
 */
public class SignInRequiredException extends SourceAccessException {

    private final transient SignInChallenge challenge;

    public SignInRequiredException(String sourceId, String message, SignInChallenge challenge, Throwable cause) {
        super(sourceId, Reason.ACCESS_DENIED, message, cause);
        this.challenge = Objects.requireNonNull(challenge, "challenge");
    }

    public SignInChallenge challenge() {
        return challenge;
    }
}
