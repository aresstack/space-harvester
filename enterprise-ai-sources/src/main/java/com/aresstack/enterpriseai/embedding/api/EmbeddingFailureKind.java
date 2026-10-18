package com.aresstack.enterpriseai.embedding.api;

/**
 * Portneutrale Fehlerklasse eines Embedding-Aufrufs. Adapter bilden ihre technischen Fehler (HTTP-Status,
 * Socket-Fehler, JSON-Fehler) darauf ab; Aufrufer entscheiden anhand von {@link #isRetryable()} über
 * Wiederholungen.
 */
public enum EmbeddingFailureKind {

    /** Verbindung nicht möglich, Timeout, Abbruch während der Übertragung. */
    UNAVAILABLE(true),
    /** Zugangsdaten fehlen, sind ungültig oder berechtigen nicht (z. B. HTTP 401/403). */
    AUTHENTICATION(false),
    /** Provider drosselt (z. B. HTTP 429). */
    RATE_LIMITED(true),
    /** Provider lehnt die Anfrage ab (z. B. HTTP 400/404/413/422: Modell unbekannt, Eingabe zu lang). */
    REJECTED(false),
    /** Interner Fehler des Providers (z. B. HTTP 5xx). */
    PROVIDER_ERROR(true),
    /** Antwort verletzt den Vertrag: unlesbar, falsche Anzahl, falsche Dimension, nicht-endliche Werte. */
    INVALID_RESPONSE(false);

    private final boolean retryable;

    EmbeddingFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
