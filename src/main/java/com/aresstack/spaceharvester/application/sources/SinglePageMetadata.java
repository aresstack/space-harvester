package com.aresstack.spaceharvester.application.sources;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadaten einer einzelnen Seite samt {@code metadataHash} und {@code attributesHash}, live aus der Quelle gelesen.
 *
 * @param sourceId    Quellen-ID
 * @param entry       die Seite mit ihren Fingerprints
 * @param retrievedAt Zeitpunkt der Abfrage bei der Quelle (nicht Teil der Hashes)
 */
public record SinglePageMetadata(String sourceId, PageMetadataListing.Entry entry, Instant retrievedAt) {

    public SinglePageMetadata {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }
}
