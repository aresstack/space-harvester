package com.aresstack.spaceharvester.application.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Durchgereichtes Token eines Endnutzers für genau eine Quelle (#24, Teil C). Lebt nur für die Dauer einer Anfrage,
 * wird nie gespeichert, geloggt oder in Antworten, Jobs oder Exporte übernommen und nur an diese Quelle gesendet.
 * Gleichheit nur bei gleicher Quelle und gleichem Token; {@link #toString()} zeigt es nicht.
 */
public final class UserCredential {

    private final String sourceId;
    private final String token;
    private final String fingerprint;

    public UserCredential(String sourceId, String token) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Leeres Token");
        }
        this.token = token;
        this.fingerprint = sha256(sourceId + "\n" + token);
    }

    /** Quelle, für die das Token gilt; für jede andere Quelle ist es wertlos und wird nicht verwendet. */
    public String sourceId() {
        return sourceId;
    }

    /** Das Token selbst, nur für den Aufruf der Quelle. */
    public String token() {
        return token;
    }

    /** SHA-256 aus Quelle und Token als Schlüssel für kurzlebige Zwischenspeicher; lässt das Token nicht erkennen. */
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UserCredential that && fingerprint.equals(that.fingerprint);
    }

    @Override
    public int hashCode() {
        return fingerprint.hashCode();
    }

    @Override
    public String toString() {
        return "UserCredential[" + sourceId + ", ***]";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
