package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Objects;

/**
 * Eine gecachte Seite mit dem Cache-Stand, aus dem sie stammt.
 *
 * @param snapshot   nativer Snapshot (Kopfdaten und unveränderte Payload)
 * @param generation Generation des Space, zu der dieser Snapshot gehört
 */
public record CachedPage(PageSnapshot snapshot, CacheGeneration generation) {

    public CachedPage {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(generation, "generation");
    }
}
