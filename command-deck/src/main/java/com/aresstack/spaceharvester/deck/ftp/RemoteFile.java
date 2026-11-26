package com.aresstack.spaceharvester.deck.ftp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Der Text einer Datei auf dem Host, wie ein Editor ihn zeigt (Satzende als Zeilenumbruch), mit Prüfsumme, damit beim
 * Zurückschreiben auffällt, ob jemand anderes inzwischen geändert hat (wie {@code writeIfUnchanged} in MainframeMate).
 *
 * @param path absoluter Pfad, unter dem gelesen wurde
 * @param text Inhalt
 * @param hash SHA-256 des Inhalts
 */
public record RemoteFile(String path, String text, String hash, String writeRestriction) {

    public RemoteFile(String path, String text, String hash) {
        this(path, text, hash, "");
    }

    public RemoteFile {
        Objects.requireNonNull(path, "path");
        text = text == null ? "" : text;
        hash = hash == null ? hash(text) : hash;
        writeRestriction = writeRestriction == null ? "" : writeRestriction;
    }

    public static RemoteFile of(String path, String text) {
        return new RemoteFile(path, text, null);
    }

    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
