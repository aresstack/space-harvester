package com.aresstack.spaceharvester.app.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Der Bereich, zu dem Chats gehören (Angelo, 16.11.2026): was links im Baum gewählt ist, eine Quelle, ein Space oder
 * eine Seite samt Unterseiten. Chats sind keine globale Liste, sondern liegen je Bereich in einem eigenen Ordner.
 *
 * @param sourceId die Quelle
 * @param spaceKey der Space oder {@code null} für die ganze Quelle
 * @param pageId   die Seite (samt Unterseiten) oder {@code null} für den ganzen Space
 */
public record ChatNode(String sourceId, String spaceKey, String pageId) {

    public ChatNode {
        Objects.requireNonNull(sourceId, "sourceId");
        if (pageId != null && spaceKey == null) {
            throw new IllegalArgumentException("Eine Seite braucht ihren Space");
        }
    }

    /**
     * Der Ordner der Chats dieses Bereichs unter {@code root}: {@code <Quelle>/<Space>/<Seite>}, jeder Teil als
     * lesbarer, dateisicherer Name; Teile mit anderen Zeichen bekommen einen kurzen Hash angehängt, damit zwei
     * Kennungen nie denselben Ordner teilen.
     */
    public Path directory(Path root) {
        return root.resolve(segment(sourceId)).resolve(spaceKey == null ? "_quelle" : segment(spaceKey))
                .resolve(pageId == null ? "_space" : segment(pageId));
    }

    static String segment(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 60) {
            safe = safe.substring(0, 60);
        }
        if (safe.equals(value) && !safe.startsWith(".") && !safe.startsWith("_")) {
            return safe;
        }
        return safe + "-" + hash(value);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
