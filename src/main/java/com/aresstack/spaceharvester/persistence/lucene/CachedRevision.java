package com.aresstack.spaceharvester.persistence.lucene;

import java.time.Instant;
import java.util.Objects;

/**
 * Eine gespeicherte Revision einer Seite im Verlauf, ohne Payload. Je Seite und Revision gibt es höchstens einen
 * Eintrag; ein erneutes Speichern derselben Revision ersetzt ihn (zuletzt gesehene Bytes und Abrufzeit).
 *
 * @param revision       Revision der Quelle; leer, wenn die Quelle keine meldet
 * @param rawSha256      {@code sha256:<hex>} der Payload dieser Revision
 * @param metadataHash   {@code metadataHash} dieser Revision
 * @param attributesHash {@code attributesHash} dieser Revision
 * @param retrievedAt    Zeitpunkt der Antwort der Quelle
 */
public record CachedRevision(String revision, String rawSha256, String metadataHash, String attributesHash,
                             Instant retrievedAt) {

    public CachedRevision {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(rawSha256, "rawSha256");
        Objects.requireNonNull(metadataHash, "metadataHash");
        Objects.requireNonNull(attributesHash, "attributesHash");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }
}
