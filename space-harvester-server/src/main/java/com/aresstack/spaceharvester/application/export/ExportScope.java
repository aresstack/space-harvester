package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import java.util.Objects;

/**
 * Was ein Export liest: ein Space in genau einer Cache-Generation, damit kein Gemisch zweier Abgleiche als ein Stand
 * erscheint.
 *
 * @param sourceId   Quelle
 * @param spaceKey   Space
 * @param generation Generation, wie sie der Cache zu Beginn des Exports ausweist
 */
public record ExportScope(String sourceId, String spaceKey, CacheGeneration generation) {

    public ExportScope {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(generation, "generation");
    }
}
