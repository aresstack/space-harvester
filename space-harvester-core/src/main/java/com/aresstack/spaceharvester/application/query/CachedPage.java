package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Objects;

/**
 * Eine gecachte Seite mit Payload und dem Cache-Stand, aus dem sie stammt.
 *
 * @param header     aktuelle Kopfdaten; können neuer sein als die des Snapshots (#16 aktualisiert sie ohne
 *                   Body-Abruf) und entscheiden über Sichtbarkeit und Space
 * @param snapshot   nativer Snapshot (Kopfdaten zur Payload und die unveränderten Bytes)
 * @param generation Generation des Space, zu der die Seite gehört
 */
public record CachedPage(PageHeader header, PageSnapshot snapshot, CacheGeneration generation) {

    public CachedPage {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(generation, "generation");
        if (!header.key().equals(snapshot.key())) {
            throw new IllegalArgumentException("Kopfdaten und Snapshot gehören zu verschiedenen Seiten");
        }
    }
}
