package com.aresstack.spaceharvester.application.assets;

/** Ein Bild konnte nicht beschrieben werden; die Meldung nennt nur die Art des Fehlers. */
public class ImageDescriptionException extends RuntimeException {

    /** Warum keine Beschreibung entstand. */
    public enum Reason {
        /** Kein Beschreiber konfiguriert. */
        NOT_CONFIGURED,
        /** Die Quelle ist nicht für den externen KI-Dienst freigegeben ({@code external-processing-sources}). */
        NOT_RELEASED,
        /** Der Anhang ist kein Bild (draw.io und PDF brauchen keine Bildbeschreibung). */
        NOT_AN_IMAGE,
        /** Noch keine Beschreibung für diesen Stand des Bildes. */
        NOT_FOUND,
        /** Schon zu viele Beschreibungen laufen gleichzeitig, oder die desselben Bildes dauert zu lange. */
        BUSY,
        /** Das Modell war nicht erreichbar oder hat keine verwertbare Antwort geliefert. */
        FAILED
    }

    private final Reason reason;

    public ImageDescriptionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
