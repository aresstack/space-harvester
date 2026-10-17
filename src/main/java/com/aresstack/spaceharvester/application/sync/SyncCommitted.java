package com.aresstack.spaceharvester.application.sync;

/**
 * Folgearbeit nach einer bestätigten Generation, z. B. Anreicherungen (#29). Läuft, nachdem der Commit sichtbar ist;
 * was hier scheitert, ändert weder die Generation noch den Ausgang des Abgleichs.
 */
@FunctionalInterface
public interface SyncCommitted {

    /** @param run ein Lauf mit {@link SpaceSync.SyncRun#confirmed()} ungleich {@code null} */
    void committed(SpaceSync.SyncRun run);
}
