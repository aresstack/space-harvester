package com.aresstack.enterpriseai.speech.api;

/** Die Sprachausgabe ist nicht erreichbar oder das Modell kann den Text nicht sprechen; ohne Secrets in der Meldung. */
public class SpeechSynthesisException extends Exception {

    private static final long serialVersionUID = 1L;

    public SpeechSynthesisException(String message) {
        super(message);
    }

    public SpeechSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
