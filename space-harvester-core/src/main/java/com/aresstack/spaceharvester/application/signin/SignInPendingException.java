package com.aresstack.spaceharvester.application.signin;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.util.Objects;

/**
 * Die Quelle liefert eine Datei erst nach einem Einmalcode, und die Anforderung dafür ist offen (ADR 0018, Lesen auf
 * Abruf): die Oberfläche zeigt die Eingabe für {@link #signInRequestId()} und lädt nach der Freischaltung neu. Grund
 * {@link Reason#ACCESS_DENIED}, damit Aufrufer ohne Anmeldung wie bisher „keine Berechtigung“ sehen. Der Text nennt
 * weder Code noch Token.
 */
public class SignInPendingException extends SourceAccessException {

    private final String signInRequestId;

    public SignInPendingException(String sourceId, String signInRequestId, Throwable cause) {
        super(sourceId, Reason.ACCESS_DENIED, "Die Quelle verlangt einen Einmalcode", cause);
        this.signInRequestId = Objects.requireNonNull(signInRequestId, "signInRequestId");
    }

    /** Die offene Anforderung ({@link SignInRequests#request}), die der Aufrufer beantworten kann. */
    public String signInRequestId() {
        return signInRequestId;
    }
}
