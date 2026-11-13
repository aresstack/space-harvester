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
 * @param partsWithoutFile Teile, die die Quelle beim Erzeugen der Datei auslassen musste (ADR 0026, AP7c: angehängtes
 *                     Outlook-Element mit Verweisen oder OLE-Objekten); gehört zur Quelle, nicht zu den Bytes, und
 *                     macht ein vollständig gelesenes Dokument {@code PARTIAL/entries-not-readable}
 */
public record Asset(String sourceId, String pageId, String attachmentId, String version, String title,
                    String mediaType, AssetKind kind, long size, String sha256, Instant retrievedAt,
                    int partsWithoutFile) {

    /** Ein Anhang, bei dem die Quelle nichts auslassen musste. */
    public Asset(String sourceId, String pageId, String attachmentId, String version, String title, String mediaType,
                 AssetKind kind, long size, String sha256, Instant retrievedAt) {
        this(sourceId, pageId, attachmentId, version, title, mediaType, kind, size, sha256, retrievedAt, 0);
    }

    /** Derselbe Anhang mit der Zahl ausgelassener Teile laut aktueller Liste der Quelle. */
    public Asset withPartsWithoutFile(int parts) {
        return parts == partsWithoutFile ? this : new Asset(sourceId, pageId, attachmentId, version, title, mediaType,
                kind, size, sha256, retrievedAt, parts);
    }

    public Asset {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        title = title == null ? "" : title;
        mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        if (partsWithoutFile < 0) {
            throw new IllegalArgumentException("partsWithoutFile darf nicht negativ sein");
        }
        if (!sha256.startsWith("sha256:")) {
            throw new IllegalArgumentException("sha256 braucht das Präfix sha256:");
        }
    }
}
