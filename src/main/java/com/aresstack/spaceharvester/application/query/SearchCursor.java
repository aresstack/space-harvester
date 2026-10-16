package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Undurchsichtiger Cursor der Suche: Fingerabdruck der Anfrage, Indexgeneration und Position des letzten
 * ausgelieferten, also sichtbaren Treffers. Er nennt nie einen Treffer, den der Aufrufer nicht schon gesehen hat, und
 * gilt nur für dieselbe Anfrage.
 */
record SearchCursor(String query, String generation, String afterPosition) {

    private static final String PREFIX = "s1.";
    private static final char SEPARATOR = '\n';

    static SearchCursor after(PageSearchQuery query, String generation, String afterPosition) {
        return new SearchCursor(fingerprint(query), generation, afterPosition);
    }

    String encode() {
        String plain = query + SEPARATOR + generation + SEPARATOR + afterPosition;
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidQueryException wenn der Cursor nicht lesbar ist oder zu einer anderen Anfrage gehört
     */
    static SearchCursor decode(String cursor, PageSearchQuery query) {
        String[] parts = null;
        if (cursor.startsWith(PREFIX)) {
            try {
                // Höchstens drei Teile: die Position stammt vom Index und darf selbst Zeilenumbrüche enthalten.
                parts = new String(Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length())),
                        StandardCharsets.UTF_8).split(String.valueOf(SEPARATOR), 3);
            } catch (IllegalArgumentException e) {
                parts = null;
            }
        }
        if (parts == null || parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InvalidQueryException("cursor ist ungültig");
        }
        if (!parts[0].equals(fingerprint(query))) {
            throw new InvalidQueryException("cursor gehört zu einer anderen Suchanfrage");
        }
        return new SearchCursor(parts[0], parts[1], parts[2]);
    }

    private static String fingerprint(PageSearchQuery query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(query.canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }
}
