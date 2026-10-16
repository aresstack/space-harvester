package com.aresstack.spaceharvester.persistence.lucene;

import java.time.Instant;

/**
 * Kopf der bestätigten Generation eines Space ({@code SyncBaseline}) ohne den Stand je Seite, für Abfragen, die nur
 * Nummer und Zeitpunkt brauchen.
 *
 * @param stalePageCount Zahl der Seiten, deren Stand aus einer älteren Generation übernommen wurde
 */
public record ConfirmedGeneration(String sourceId, String spaceKey, long generation, Instant confirmedAt,
                                  int stalePageCount) {
}
