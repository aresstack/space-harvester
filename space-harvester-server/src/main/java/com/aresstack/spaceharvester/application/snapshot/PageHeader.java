package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * Aktuelle Kopfdaten einer Seite, unabhängig von der gespeicherten Payload. Die Synchronisierung (#16) aktualisiert
 * sie ohne Body-Abruf, wenn sich nur Attribute (Labels) oder Titel, Space und Elternseite bei gleicher Revision
 * geändert haben. Die Kopfdaten eines {@link PageSnapshot} bleiben dabei unverändert, weil sie zu dessen Payload
 * gehören.
 *
 * @param observedAt Zeitpunkt, zu dem die Quelle diese Kopfdaten geliefert hat
 */
public record PageHeader(PageKey key, PageMetadata metadata, String metadataHash, String attributesHash,
                         Instant observedAt) {

    public PageHeader {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(metadataHash, "metadataHash");
        Objects.requireNonNull(attributesHash, "attributesHash");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!key.pageId().equals(metadata.pageId())) {
            throw new IllegalArgumentException("Seiten-ID der Kopfdaten passt nicht zum Schlüssel");
        }
    }

    /** Kopfdaten mit den Hashes, berechnet aus den Metadaten. */
    public static PageHeader of(String sourceId, PageMetadata metadata, Instant observedAt) {
        return new PageHeader(new PageKey(sourceId, metadata.pageId()), metadata, MetadataHash.of(sourceId, metadata),
                AttributesHash.of(sourceId, metadata), observedAt);
    }

    /** Revision der Quelle oder {@code null}, wenn unbekannt. */
    public String revision() {
        return metadata.version();
    }
}
