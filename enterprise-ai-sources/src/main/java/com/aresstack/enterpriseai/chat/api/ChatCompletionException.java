package com.aresstack.enterpriseai.chat.api;

/**
 * Fehler eines Chat-Providers. Die Meldung ist für Nutzer und Logs bestimmt und darf deshalb weder
 * Zugangsdaten noch Header oder vollständige Anfrageinhalte enthalten; Adapter kürzen Serverantworten.
 */
public class ChatCompletionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ChatErrorKind kind;
    private final int statusCode;

    public ChatCompletionException(ChatErrorKind kind, String message) {
        this(kind, -1, message, null);
    }

    public ChatCompletionException(ChatErrorKind kind, String message, Throwable cause) {
        this(kind, -1, message, cause);
    }

    /**
     * @param statusCode protokollspezifischer Status (z. B. HTTP-Code) oder -1, wenn es keinen gibt
     */
    public ChatCompletionException(ChatErrorKind kind, int statusCode, String message, Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public ChatErrorKind kind() {
        return kind;
    }

    /** @return protokollspezifischer Status oder -1 */
    public int statusCode() {
        return statusCode;
    }
}
