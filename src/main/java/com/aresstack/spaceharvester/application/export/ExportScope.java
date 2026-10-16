package com.aresstack.spaceharvester.application.export;

import java.util.Objects;

/**
 * Was ein Export liest: eine Partition in einer bestimmten Cache-Generation. Der Leser liefert alle Seiten eines
 * Exports aus dieser einen Generation, damit kein Gemisch zweier Abgleiche als ein Stand erscheint.
 *
 * @param sourceId     Quelle
 * @param partitionKey Partition (Space)
 * @param generation   Kennung der Cache-Generation, vom Leser vergeben
 * @param state        Stand dieser Generation
 */
public record ExportScope(String sourceId, String partitionKey, String generation, CacheState state) {

    public ExportScope {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(state, "state");
    }
}
