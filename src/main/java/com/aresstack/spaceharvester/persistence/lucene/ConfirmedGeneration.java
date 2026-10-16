package com.aresstack.spaceharvester.persistence.lucene;

import java.time.Instant;

/**
 * Kopf der bestätigten Generation eines Space ({@code SyncBaseline}) ohne den Stand je Seite, für Abfragen, die nur
 * Nummer und Zeitpunkt brauchen.
 *
 * @param stalePageCount   Zahl der Seiten, deren Stand aus einer älteren Generation übernommen wurde
 * @param missingPageCount Zahl der gelisteten Seiten ohne Stand im Cache ({@code SyncBaseline#missingPageIds})
 */
public record ConfirmedGeneration(String sourceId, String spaceKey, long generation, Instant confirmedAt,
                                  int stalePageCount, int missingPageCount) {

    public ConfirmedGeneration(String sourceId, String spaceKey, long generation, Instant confirmedAt,
                               int stalePageCount) {
        this(sourceId, spaceKey, generation, confirmedAt, stalePageCount, 0);
    }
}
