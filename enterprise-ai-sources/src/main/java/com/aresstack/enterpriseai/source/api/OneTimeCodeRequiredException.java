package com.aresstack.enterpriseai.source.api;

/**
 * Die Quelle liefert eine Datei erst nach einem Einmalcode ({@link OneTimeCodeChallenge}); Art
 * {@link Kind#ACCESS_DENIED}, damit Aufrufer ohne Abfrage-Oberfläche sie wie eine verweigerte Datei behandeln.
 */
public class OneTimeCodeRequiredException extends KnowledgeSourceException {

    private static final long serialVersionUID = 1L;

    private final transient OneTimeCodeChallenge challenge;

    public OneTimeCodeRequiredException(String message, OneTimeCodeChallenge challenge, Throwable cause) {
        super(Kind.ACCESS_DENIED, message, cause);
        if (challenge == null) {
            throw new IllegalArgumentException("challenge must not be null");
        }
        this.challenge = challenge;
    }

    public OneTimeCodeChallenge challenge() {
        return challenge;
    }
}
