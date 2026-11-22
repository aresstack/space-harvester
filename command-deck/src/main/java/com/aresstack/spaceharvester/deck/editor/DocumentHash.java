package com.aresstack.spaceharvester.deck.editor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerabdruck von Dateiinhalten (SHA-256, hex), wie {@code FilePayload.getHash()} in MainframeMate. */
public final class DocumentHash {

    private DocumentHash() {
    }

    public static String of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in der Laufzeit", e);
        }
    }
}
