package com.aresstack.spaceharvester.app.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Der Bereich, zu dem Chats gehören (Angelo, 16.11.2026): was links im Baum gewählt ist, eine Quelle, ein Space oder
 * eine Seite samt Unterseiten, ein Favoriten-Ordner (Sammlung, ADR 0034 §5) oder alle Wissensquellen (die Überschrift
 * des Explorers, {@link #ALL}). Chats sind keine globale Liste, sondern liegen je Bereich in einem eigenen Ordner.
 *
 * @param sourceId die Quelle oder {@code null} für alle Wissensquellen bzw. eine Sammlung
 * @param spaceKey der Space oder {@code null} für die ganze Quelle
 * @param pageId   die Seite (samt Unterseiten) oder {@code null} für den ganzen Space
 * @param folderId der Favoriten-Ordner einer Sammlung, sonst {@code null}; schließt Quelle, Space und Seite aus
 */
public record ChatNode(String sourceId, String spaceKey, String pageId, String folderId) {

    /** Alle Wissensquellen: die Chats zur globalen Auswahl liegen unter {@code _alle}. */
    public static final ChatNode ALL = new ChatNode(null, null, null);

    public ChatNode {
        if (folderId != null && sourceId != null) {
            throw new IllegalArgumentException("Eine Sammlung hat keine Quelle");
        }
        if (sourceId == null && (spaceKey != null || pageId != null)) {
            throw new IllegalArgumentException("Ein Space braucht seine Quelle");
        }
        if (pageId != null && spaceKey == null) {
            throw new IllegalArgumentException("Eine Seite braucht ihren Space");
        }
    }

    /** Ein Knoten im Baum: Quelle, Space oder Seite samt Unterseiten; ohne Quelle {@link #ALL}. */
    public ChatNode(String sourceId, String spaceKey, String pageId) {
        this(sourceId, spaceKey, pageId, null);
    }

    /**
     * Ein Favoriten-Ordner (ADR 0034 §5): Seine Chats liegen bei der Ordner-ID, nicht bei seinem Namen; Umbenennen und
     * Verschieben behalten sie, die Stellung „mit Unterseiten“ teilt sie nicht auf.
     */
    public static ChatNode collection(String folderId) {
        return new ChatNode(null, null, null, java.util.Objects.requireNonNull(folderId, "folderId"));
    }

    /** Ob dies eine Sammlung ist. */
    public boolean isCollection() {
        return folderId != null;
    }

    /**
     * Der Ordner der Chats dieses Bereichs unter {@code root}: {@code <Quelle>/<Space>/<Seite>}, eine Sammlung unter
     * {@code _sammlung/<Ordner>}, jeder Teil als lesbarer, dateisicherer Name; Teile mit anderen Zeichen bekommen einen
     * kurzen Hash angehängt, damit zwei Kennungen nie denselben Ordner teilen.
     */
    public Path directory(Path root) {
        if (folderId != null) {
            return root.resolve("_sammlung").resolve(segment(folderId));
        }
        if (sourceId == null) {
            return root.resolve("_alle");
        }
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
