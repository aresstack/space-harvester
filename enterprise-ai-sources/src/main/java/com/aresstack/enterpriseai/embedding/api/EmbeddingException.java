package com.aresstack.enterpriseai.embedding.api;

/**
 * Portneutraler Fehler eines {@link EmbeddingPort}. Nachricht und Ursache enthalten keine Secrets und keine
 * Provider-Typen; die Ursache darf eine JDK-Ausnahme (z. B. {@link java.io.IOException}) sein.
 */
public class EmbeddingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final EmbeddingFailureKind kind;

    public EmbeddingException(EmbeddingFailureKind kind, String message) {
        this(kind, message, null);
    }

    public EmbeddingException(EmbeddingFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
    }

    public EmbeddingFailureKind kind() {
        return kind;
    }

    public boolean isRetryable() {
        return kind.isRetryable();
    }
}
