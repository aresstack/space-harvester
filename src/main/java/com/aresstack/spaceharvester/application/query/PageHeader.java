package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.util.Objects;

/**
 * Kopfdaten einer gecachten Seite ohne Payload: die Projektion eines {@link PageSnapshot}, die Seitenliste und Baum
 * brauchen, ohne die nativen Bytes zu laden.
 *
 * @param key            Quelle und Seite
 * @param metadata       Kopfdaten aus dem Snapshot
 * @param metadataHash   wie im Snapshot
 * @param attributesHash wie im Snapshot
 * @param rawSha256      {@code sha256:<hex>} der gespeicherten Payload
 */
public record PageHeader(PageKey key, PageMetadata metadata, String metadataHash, String attributesHash,
                         String rawSha256) {

    public PageHeader {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(metadataHash, "metadataHash");
        Objects.requireNonNull(attributesHash, "attributesHash");
        Objects.requireNonNull(rawSha256, "rawSha256");
        if (!key.pageId().equals(metadata.pageId())) {
            throw new IllegalArgumentException("Seiten-ID der Kopfdaten passt nicht zum Schlüssel");
        }
    }

    public static PageHeader of(PageSnapshot snapshot) {
        return new PageHeader(snapshot.key(), snapshot.metadata(), snapshot.metadataHash(), snapshot.attributesHash(),
                snapshot.payload().rawSha256());
    }

    public String pageId() {
        return key.pageId();
    }

    public String spaceKey() {
        return metadata.partitionKey();
    }

    public String parentPageId() {
        return metadata.parentPageId();
    }
}
