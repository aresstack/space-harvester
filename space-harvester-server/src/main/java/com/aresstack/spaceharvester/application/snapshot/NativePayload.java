package com.aresstack.spaceharvester.application.snapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Die Antwort der Quelle Byte für Byte (HTTP-Body nach Transportdekodierung), nie aus geparsten Objekten
 * rekonstruiert, ohne Header, Cookies oder Zugangsdaten.
 *
 * <p>{@link #rawSha256()} ist {@code sha256:<hex>} über genau diese Bytes. Er belegt, dass ein gespeicherter Snapshot
 * unverändert ist, zeigt aber keine Inhaltsänderung an: Dieselbe Revision kann wegen volatiler Felder (Links,
 * Benutzerobjekte) andere Bytes liefern (ADR 0002). Änderungen erkennt die Synchronisierung über Revision,
 * {@code metadataHash} und {@code attributesHash}.
 *
 * @param mediaType    Medientyp ohne Parameter, z. B. {@code application/json}
 * @param nativeFormat Format und Abrufform der Quelle, z. B.
 *                     {@code confluence-rest-content+json;expand=ancestors,body.storage,…}; Payloads mit anderem
 *                     Wert sind nicht vergleichbar
 */
public final class NativePayload {

    private final byte[] bytes;
    private final String mediaType;
    private final String nativeFormat;
    private final String rawSha256;

    public NativePayload(byte[] bytes, String mediaType, String nativeFormat) {
        Objects.requireNonNull(bytes, "bytes");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.nativeFormat = Objects.requireNonNull(nativeFormat, "nativeFormat");
        this.bytes = bytes.clone();
        this.rawSha256 = "sha256:" + sha256Hex(this.bytes);
    }

    /** Eine Kopie der Bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    public String mediaType() {
        return mediaType;
    }

    public String nativeFormat() {
        return nativeFormat;
    }

    public String rawSha256() {
        return rawSha256;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NativePayload that && Arrays.equals(bytes, that.bytes)
                && mediaType.equals(that.mediaType) && nativeFormat.equals(that.nativeFormat);
    }

    @Override
    public int hashCode() {
        return rawSha256.hashCode();
    }

    /** Nie der Inhalt: die Payload ist schutzbedürftig wie die Seite selbst. */
    @Override
    public String toString() {
        return "NativePayload[" + bytes.length + " bytes, " + mediaType + ", " + nativeFormat + ", " + rawSha256 + "]";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
