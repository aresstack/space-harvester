package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * Nativer Snapshot einer Seite: Kopfdaten und unveränderte Payload aus <em>derselben</em> Antwort der Quelle, damit
 * Revision und Inhalt zusammengehören. Kopfdaten und Payload bleiben getrennt; Metadaten werden nie in die Payload
 * geschrieben.
 *
 * @param key            Quelle und Seite
 * @param metadata       Kopfdaten aus der Payload (Titel, Space, Elternseite, Revision, Änderungszeit, Attribute)
 * @param metadataHash   {@code metadataHash} nach {@link MetadataHash#SCHEMA}
 * @param attributesHash {@code attributesHash} nach {@link AttributesHash#SCHEMA}
 * @param payload        die Antwort der Quelle Byte für Byte
 * @param retrievedAt    Zeitpunkt der Antwort der Quelle
 */
public record PageSnapshot(PageKey key, PageMetadata metadata, String metadataHash, String attributesHash,
                           NativePayload payload, Instant retrievedAt) {

    public PageSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(metadataHash, "metadataHash");
        Objects.requireNonNull(attributesHash, "attributesHash");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        if (!key.pageId().equals(metadata.pageId())) {
            throw new IllegalArgumentException("Seiten-ID der Kopfdaten passt nicht zum Schlüssel");
        }
    }

    /** Snapshot mit den Hashes, berechnet aus den Kopfdaten. */
    public static PageSnapshot of(String sourceId, PageMetadata metadata, NativePayload payload, Instant retrievedAt) {
        return new PageSnapshot(new PageKey(sourceId, metadata.pageId()), metadata,
                MetadataHash.of(sourceId, metadata), AttributesHash.of(sourceId, metadata), payload, retrievedAt);
    }

    /** Die Kopfdaten dieses Snapshots (aus seiner Payload) als {@link PageHeader}. */
    public PageHeader header() {
        return new PageHeader(key, metadata, metadataHash, attributesHash, retrievedAt);
    }

    /** Revision der Quelle oder {@code null}, wenn unbekannt. */
    public String revision() {
        return metadata.version();
    }

    /** Space-Key zum Zeitpunkt des Abrufs. */
    public String spaceKey() {
        return metadata.partitionKey();
    }

    /** Elternseite oder {@code null} für eine Wurzelseite. */
    public String parentPageId() {
        return metadata.parentPageId();
    }
}
