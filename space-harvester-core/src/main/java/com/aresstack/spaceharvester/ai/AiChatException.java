package com.aresstack.spaceharvester.ai;

import java.util.Objects;

/** Fehler des Chat-Modells, quellenunabhängig: Art, HTTP-Status (oder -1) und die technische Ursache des Adapters. */
public class AiChatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        AUTHENTICATION,
        CREDENTIALS_UNAVAILABLE,
        RATE_LIMITED,
        INVALID_REQUEST,
        PROVIDER_ERROR,
        TRANSPORT,
        PROTOCOL
    }

    private final Kind kind;
    private final int statusCode;

    public AiChatException(Kind kind, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.statusCode = statusCode;
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }
}
