package com.aresstack.spaceharvester.persistence.lucene;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Gespeicherter Synchronisierungsstand eines Space. Jeder aufgezeichnete Lauf erhöht {@link #generation()}; nur ein
 * als vollständig gemeldeter Lauf wird zur bestätigten Generation. Ein unvollständiger Lauf lässt die zuletzt
 * bestätigte Generation stehen und macht nur sichtbar, dass der letzte Lauf nicht vollständig war.
 *
 * <p>Was „vollständig“ heißt, entscheidet die Synchronisierung (#16); der Cache übernimmt die Meldung und täuscht nie
 * einen vollständigen Stand vor, den niemand gemeldet hat.
 *
 * @param sourceId              Quelle
 * @param spaceKey              Space
 * @param generation            Nummer des zuletzt aufgezeichneten Laufs, ab 1
 * @param lastRunComplete       ob der zuletzt aufgezeichnete Lauf vollständig war
 * @param lastRunAt             Ende des zuletzt aufgezeichneten Laufs
 * @param confirmedGeneration   Nummer des letzten vollständigen Laufs, 0 wenn es keinen gab
 * @param confirmedAt           Ende des letzten vollständigen Laufs oder {@code null}
 * @param confirmedPageCount    Seitenzahl des letzten vollständigen Laufs, 0 wenn es keinen gab
 */
public record SpaceSyncState(String sourceId, String spaceKey, long generation, boolean lastRunComplete,
                             Instant lastRunAt, long confirmedGeneration, Instant confirmedAt,
                             long confirmedPageCount) {

    public SpaceSyncState {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(lastRunAt, "lastRunAt");
    }

    /** Ob es überhaupt einen bestätigt vollständigen Lauf gab. */
    public boolean everConfirmed() {
        return confirmedGeneration > 0;
    }

    /** Ende des letzten vollständigen Laufs, leer wenn es keinen gab. */
    public Optional<Instant> confirmed() {
        return Optional.ofNullable(confirmedAt);
    }

    /** Der Stand nach einem weiteren Lauf. */
    SpaceSyncState next(boolean complete, Instant finishedAt, long pageCount) {
        long run = generation + 1;
        return complete
                ? new SpaceSyncState(sourceId, spaceKey, run, true, finishedAt, run, finishedAt, pageCount)
                : new SpaceSyncState(sourceId, spaceKey, run, false, finishedAt, confirmedGeneration, confirmedAt,
                        confirmedPageCount);
    }

    /** Der Stand vor dem ersten Lauf, nur zum Fortschreiben mit {@link #next}. */
    static SpaceSyncState none(String sourceId, String spaceKey) {
        return new SpaceSyncState(sourceId, spaceKey, 0, false, Instant.EPOCH, 0, null, 0);
    }
}
