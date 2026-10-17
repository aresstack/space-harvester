package com.aresstack.spaceharvester.application.assets;

/** Ein Bild konnte nicht beschrieben werden; die Meldung nennt nur die Art des Fehlers. */
public class ImageDescriptionException extends RuntimeException {

    /** Warum keine Beschreibung entstand. */
    public enum Reason {
        /** Kein Beschreiber konfiguriert. */
        NOT_CONFIGURED,
        /** Der Anhang ist kein Bild (draw.io und PDF brauchen keine Bildbeschreibung). */
        NOT_AN_IMAGE,
        /** Noch keine Beschreibung für diesen Stand des Bildes. */
        NOT_FOUND,
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
