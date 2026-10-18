package com.aresstack.spaceharvester.application.sync;

/**
 * Folgearbeit nach einer bestätigten Generation, z. B. Anreicherungen (#29). Läuft, nachdem der Commit sichtbar ist;
 * was hier scheitert, ändert weder die Generation noch den Ausgang des Abgleichs.
 */
@FunctionalInterface
public interface SyncCommitted {

    /** @param run ein Lauf mit {@link SpaceSync.SyncRun#confirmed()} ungleich {@code null} */
    void committed(SpaceSync.SyncRun run);

    /**
     * Wie {@link #committed(SpaceSync.SyncRun)}, mit dem Auslöser des Abgleichs oder Exports, damit eigene Aufträge
     * der Folgearbeit für ihn sichtbar sind.
     *
     * @param owner auslösender Client oder {@code null}
     */
    default void committed(SpaceSync.SyncRun run, String owner) {
        committed(run);
    }
}
