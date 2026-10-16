package com.aresstack.enterpriseai.source.api;

/**
 * Fehler beim Zugriff auf eine Wissensquelle.
 *
 * <p>Die Meldung beschreibt den Fehler fachlich (Quelle, Ressource, Fehlerart). Adapter dürfen weder
 * Zugangsdaten noch Tokens, Cookies oder rohe Antwortkörper in die Meldung schreiben; die Ursache
 * ({@link #getCause()}) wird nur übergeben, wenn auch sie keine solchen Daten enthält.
 */
public class KnowledgeSourceException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Kind kind;

    public KnowledgeSourceException(Kind kind, String message) {
        this(kind, message, null);
    }

    public KnowledgeSourceException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
    }

    /** Fachliche Fehlerart, nach der Aufrufer entscheiden (überspringen, erneut versuchen, abbrechen). */
    public Kind kind() {
        return kind;
    }

    /** Fehlerarten des Ports, unabhängig vom Protokoll der Quelle. */
    public enum Kind {
        /** Die Ressource existiert in der Quelle nicht (mehr). */
        NOT_FOUND,
        /** Die Quelle ist nicht erreichbar oder antwortet vorübergehend nicht (Netz, Timeout, 5xx). */
        UNAVAILABLE,
        /** Anmeldung fehlgeschlagen oder Zugriff verweigert. */
        ACCESS_DENIED,
        /** Die Quelle hat eine unerwartete oder fehlerhafte Antwort geliefert. */
        INVALID_RESPONSE,
        /** Die ID gehört nicht zu dieser Quelle (fremde Quelle oder unbekanntes ID-Format); eine fehlende Ressource dieser Quelle ist {@link #NOT_FOUND}. */
        UNSUPPORTED
    }
}
