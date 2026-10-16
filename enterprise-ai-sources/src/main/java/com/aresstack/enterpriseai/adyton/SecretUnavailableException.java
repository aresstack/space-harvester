package com.aresstack.enterpriseai.adyton;

import com.aresstack.enterpriseai.astu.security.SecretRef;

/**
 * Ein Secret konnte nicht bereitgestellt werden.
 *
 * <p>Die Nachricht nennt höchstens den (loggbaren) {@link SecretRef} und den {@link Reason}, nie
 * Secret-Material. Adapter übersetzen ihre technischen Fehler (z. B. KeePassRPC-Timeouts) in einen Grund, damit
 * Aufrufer ohne Kenntnis des Backends reagieren können (z. B. "KeePass starten" vs. "Eintrag anlegen").
 *
 * <p>Gründe nach MainframeMate ({@code KeePassNotAvailableException}, {@code AuthCancelledException}) und corenth
 * ({@code adyton.SecretUnavailableException}); anders als dort bleibt der Grund für Aufrufer unterscheidbar,
 * weil AP14 "not available" und "not found" getrennt verlangt.
 */
public class SecretUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Warum kein Secret geliefert werden konnte. */
    public enum Reason {
        /** Backend nicht erreichbar oder nicht bereit (z. B. KeePass nicht gestartet, Datenbank gesperrt). */
        NOT_AVAILABLE,
        /** Backend erreichbar, aber zur Referenz existiert kein Eintrag. */
        NOT_FOUND,
        /** Backend verweigert den Zugriff (z. B. Pairing ungültig oder widerrufen). */
        ACCESS_DENIED,
        /** Benutzer hat eine nötige Interaktion (z. B. Pairing) abgebrochen. */
        CANCELLED,
        /** Create rejected: an entry with this title already exists (it stays unchanged). */
        ALREADY_EXISTS
    }

    private final Reason reason;
    private final SecretRef ref;

    public SecretUnavailableException(Reason reason, SecretRef ref, String message) {
        this(reason, ref, message, null);
    }

    /**
     * @param message Beschreibung ohne Secret-Material
     * @param cause   technische Ursache; ihre Nachricht darf ebenfalls kein Secret-Material enthalten
     */
    public SecretUnavailableException(Reason reason, SecretRef ref, String message, Throwable cause) {
        super(describe(reason, ref, message), cause);
        if (reason == null) {
            throw new IllegalArgumentException("Grund darf nicht null sein");
        }
        this.reason = reason;
        this.ref = ref;
    }

    public Reason reason() {
        return reason;
    }

    /** Die betroffene Referenz, oder {@code null}, wenn der Fehler keine einzelne Referenz betrifft. */
    public SecretRef ref() {
        return ref;
    }

    private static String describe(Reason reason, SecretRef ref, String message) {
        StringBuilder text = new StringBuilder().append(reason);
        if (ref != null) {
            text.append(' ').append(ref);
        }
        if (message != null && !message.isEmpty()) {
            text.append(": ").append(message);
        }
        return text.toString();
    }
}
