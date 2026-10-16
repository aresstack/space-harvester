package com.aresstack.spaceharvester.application.sources;

import java.util.Objects;

/**
 * Eine Quelle konnte eine Anfrage nicht beantworten. Die Nachricht ist für API-Antworten bestimmt und enthält keine
 * Zugangsdaten.
 */
public class SourceAccessException extends RuntimeException {

    /** Art des Fehlers, damit Aufrufer Konfigurations-, Berechtigungs- und Verbindungsprobleme unterscheiden. */
    public enum Reason {
        /** Zugangsdaten fehlen, sind falsch oder reichen nicht aus. */
        ACCESS_DENIED,
        /** Quelle nicht erreichbar (Netz, Zeitüberschreitung, Wartung). */
        UNAVAILABLE,
        /** Das angefragte Objekt (z. B. ein Space) gibt es in der Quelle nicht oder es ist nicht sichtbar. */
        NOT_FOUND,
        /** Die Quelle hat unerwartet geantwortet. */
        INVALID_RESPONSE,
        /** Die Quelle unterstützt die Anfrage nicht. */
        UNSUPPORTED
    }

    private final String sourceId;
    private final Reason reason;

    public SourceAccessException(String sourceId, Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String sourceId() {
        return sourceId;
    }

    public Reason reason() {
        return reason;
    }
}
