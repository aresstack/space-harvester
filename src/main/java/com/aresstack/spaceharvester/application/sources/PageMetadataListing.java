package com.aresstack.spaceharvester.application.sources;

import java.time.Instant;
import java.util.List;

/**
 * Ein Stapel Seitenmetadaten einer Partition samt {@code metadataHash} je Seite, live aus der Quelle gelesen.
 *
 * @param sourceId     Quellen-ID
 * @param partitionKey Schlüssel der Partition (Space-Key)
 * @param entries      die Seiten mit Hash
 * @param nextCursor   Cursor für den nächsten Stapel oder {@code null}, wenn vollständig
 * @param retrievedAt  Zeitpunkt der Abfrage bei der Quelle (nicht Teil der Hashes)
 */
public record PageMetadataListing(String sourceId, String partitionKey, List<Entry> entries, String nextCursor,
                                  Instant retrievedAt) {

    public PageMetadataListing {
        entries = List.copyOf(entries);
    }

    public boolean complete() {
        return nextCursor == null;
    }

    /** Eine Seite mit ihrem Metadaten-Hash ({@link MetadataHash}). */
    public record Entry(PageMetadata page, String metadataHash) {
    }
}
