package com.aresstack.spaceharvester.application.assets;

/** Fehler der Textextraktion (#183), mit festem Grund und ohne Dateinamen oder Inhalte in der Meldung. */
public class DocumentTextException extends RuntimeException {

    /** Warum kein Text entstand. */
    public enum Reason {
        /** Keine Extraktion konfiguriert. */
        NOT_CONFIGURED,
        /** Das Format hat keinen lesbaren Text (Bild, Archiv, unbekannter Typ). */
        UNSUPPORTED,
        /** Die Datei überschreitet die Größengrenze der Extraktion. */
        TOO_LARGE,
        /** Die Extraktion lief länger als erlaubt. */
        TIMEOUT,
        /** Zu viele Extraktionen gleichzeitig; später erneut versuchen. */
        BUSY,
        /** Die Datei ist defekt, verschlüsselt oder nicht lesbar. */
        FAILED;

        /** Ob ein späterer Versuch mit denselben Bytes anders ausgehen kann. */
        public boolean transientFailure() {
            return this == TIMEOUT || this == BUSY;
        }

        /** Fester Code für Protokoll und API, z. B. {@code document-text-unsupported}. */
        public String code() {
            return "document-text-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    private final Reason reason;

    public DocumentTextException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
