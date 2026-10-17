package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Undurchsichtiger Cursor der Seitenliste: Quelle, Space, Generation und die letzte ausgelieferte, also sichtbare
 * {@code pageId}. Er nennt nie eine Seite, die der Aufrufer nicht schon gesehen hat.
 */
record PageCursor(String sourceId, String spaceKey, String generationId, String afterPageId) {

    private static final String PREFIX = "c1.";
    private static final char SEPARATOR = '\n';

    String encode() {
        String plain = sourceId + SEPARATOR + spaceKey + SEPARATOR + generationId + SEPARATOR + afterPageId;
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidQueryException wenn der Cursor nicht lesbar ist oder zu einer anderen Quelle oder einem anderen
     *                               Space gehört
     */
    static PageCursor decode(String cursor, String sourceId, String spaceKey) {
        String[] parts = null;
        if (cursor.startsWith(PREFIX)) {
            try {
                parts = new String(Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length())),
                        StandardCharsets.UTF_8).split(String.valueOf(SEPARATOR), -1);
            } catch (IllegalArgumentException e) {
                parts = null;
            }
        }
        if (parts == null || parts.length != 4 || parts[2].isEmpty() || parts[3].isEmpty()) {
            throw new InvalidQueryException("cursor ist ungültig");
        }
        if (!parts[0].equals(sourceId) || !parts[1].equals(spaceKey)) {
            throw new InvalidQueryException("cursor gehört nicht zu dieser Quelle und diesem Space");
        }
        return new PageCursor(parts[0], parts[1], parts[2], parts[3]);
    }
}
