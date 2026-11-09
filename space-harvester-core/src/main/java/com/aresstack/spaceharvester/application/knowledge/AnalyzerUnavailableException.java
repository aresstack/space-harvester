package com.aresstack.spaceharvester.application.knowledge;

/**
 * Der Satzanalysator ist nicht nutzbar: kein Analysator eingerichtet (Dienst), Modell fehlt, passt nicht zur
 * hinterlegten Version oder ist defekt. Die Meldung nennt den Grund und, wo es hilft, den Installationsweg; sie enthält
 * nie Seiteninhalte.
 */
public class AnalyzerUnavailableException extends RuntimeException {

    /** Warum. */
    public enum Reason {
        /** In dieser Laufzeit gibt es keinen Analysator (Dienst, eingebettete Bibliothek ohne {@code :nlp-parser}). */
        NOT_CONFIGURED,
        /** Modellverzeichnis oder Modelldatei fehlt. */
        MODEL_MISSING,
        /** Modell vorhanden, aber falsche Version, Prüfsumme oder unlesbar. */
        MODEL_INVALID
    }

    private final Reason reason;

    public AnalyzerUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
