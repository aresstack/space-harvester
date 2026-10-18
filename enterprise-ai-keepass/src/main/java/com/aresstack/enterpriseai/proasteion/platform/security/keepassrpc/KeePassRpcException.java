package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

/**
 * Technischer Fehler der KeePassRPC-Verbindung. Bleibt im Adapter; {@link KeePassRpcSecretProvider} übersetzt
 * ihn in {@code SecretUnavailableException}. Nachrichten enthalten nie Schlüssel, Passwörter oder entschlüsselte
 * Antworten.
 */
final class KeePassRpcException extends Exception {

    private static final long serialVersionUID = 1L;

    enum Kind {
        /** KeePass/KeePassRPC nicht erreichbar, Timeout, Verbindung abgebrochen. */
        NOT_AVAILABLE,
        /** KeePassRPC hat das Pairing bzw. den gespeicherten Schlüssel abgelehnt; neues Pairing nötig. */
        AUTH_FAILED,
        /** Unerwartete oder fehlerhafte Antwort (Protokollversion, JSON, HMAC). */
        PROTOCOL
    }

    private final Kind kind;

    KeePassRpcException(Kind kind, String message) {
        this(kind, message, null);
    }

    KeePassRpcException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }
}
