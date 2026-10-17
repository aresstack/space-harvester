package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;

/**
 * Ein Eintrag aus Listen- und Baumabfragen: die aktuellen Kopfdaten einer Seite und der Hash ihrer gespeicherten
 * Payload, ohne die Bytes zu laden.
 *
 * @param header    aktuelle Kopfdaten ({@code SnapshotRepository#findHeader})
 * @param rawSha256 {@code sha256:<hex>} der gespeicherten Payload oder {@code null}, solange keine gespeichert ist
 */
public record CachedPageHeader(PageHeader header, String rawSha256) {

    public CachedPageHeader {
        Objects.requireNonNull(header, "header");
    }

    public PageKey key() {
        return header.key();
    }
}
