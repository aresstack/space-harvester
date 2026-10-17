package com.aresstack.spaceharvester.nlp.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 als Kleinbuchstaben-Hex. */
public final class Sha256 {

    private Sha256() {
    }

    public static String of(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    public static String of(Path file) {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Datei nicht lesbar: " + file, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
