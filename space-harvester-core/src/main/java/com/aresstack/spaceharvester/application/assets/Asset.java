package com.aresstack.spaceharvester.application.assets;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein abgelegter Anhang in einer bestimmten Version, ohne Inhalt. Die Bytes liegen im {@link AssetStore} unter
 * {@code sha256}; gleiche Inhalte liegen dort nur einmal.
 *
 * @param pageId       Seite, an der der Anhang beim Abruf hing
 * @param attachmentId ID des Anhangs in der Quelle
 * @param version      Version laut Quelle oder {@code null}, wenn die Quelle keine nennt
 * @param size         Größe der abgelegten Bytes
 * @param sha256       {@code sha256:<hex>} über die abgelegten Bytes
 * @param retrievedAt  wann die Quelle die Datei geliefert hat
 */
public record Asset(String sourceId, String pageId, String attachmentId, String version, String title,
                    String mediaType, AssetKind kind, long size, String sha256, Instant retrievedAt) {

    public Asset {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        title = title == null ? "" : title;
        mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        if (!sha256.startsWith("sha256:")) {
            throw new IllegalArgumentException("sha256 braucht das Präfix sha256:");
        }
    }
}
