package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import java.time.Instant;
import java.util.Objects;

/**
 * Stand eines Space im Bestand ohne Seitenliste, etwa für die Zeilen einer Quelle.
 *
 * @param sourceId   Quellen-ID
 * @param spaceKey   Space-Key
 * @param generation Generation, aus der Katalog und Zähler stammen, wie beim Seitenbaum: {@code g<n>} nach einer
 *                   bestätigten Inventur, {@code g0}, solange nur einzelne Seiten bekannt sind (etwa durch Lesen oder
 *                   Suche); {@code null}, wenn der Cache von dem Space nichts kennt
 * @param counts     Zähler über alle sichtbaren Seiten des Space
 */
public record SpaceSummary(String sourceId, String spaceKey, CacheGeneration generation, HoldingCounts counts) {

    public SpaceSummary {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(counts, "counts");
    }

    /**
     * Ob eine Inventur bestätigt ist. Ohne sie kennt der Cache nur einzelne Seiten, deren Elternseiten oft fehlen; die
     * Zähler gelten dann nur für diese Seiten, nicht für den Space in der Quelle („keine Inventur“, nicht „nichts
     * lokal“).
     */
    public boolean inventoried() {
        return generation != null && generation.syncedAt() != null;
    }

    /** Ende der bestätigten Inventur („Stand vom“); {@code null} ohne sie. */
    public Instant asOf() {
        return generation == null ? null : generation.syncedAt();
    }
}
