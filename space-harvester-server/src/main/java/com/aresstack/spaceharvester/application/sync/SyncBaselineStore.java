package com.aresstack.spaceharvester.application.sync;

import java.util.Optional;

/**
 * Port für die bestätigten Generationen je Space. Umsetzung im Lucene-Cache (#26); hier nur der Vertrag, den die
 * Synchronisierung braucht.
 *
 * <p>Alle Schreibvorgänge eines Laufs gehen über eine {@link StagedGeneration} und werden erst mit
 * {@link StagedGeneration#commit} sichtbar, zusammen mit der neuen Generation. Leser des Caches sehen daher nie
 * Änderungen eines Laufs, dessen Generation nicht bestätigt ist.
 */
public interface SyncBaselineStore {

    /** Die zuletzt bestätigte Generation oder leer, wenn der Space noch nie vollständig abgeglichen wurde. */
    Optional<SyncBaseline> confirmed(String sourceId, String spaceKey);

    /** Beginnt die Schreibvorgänge eines Laufs für diesen Space; bis zum Commit für Leser unsichtbar. */
    StagedGeneration begin(String sourceId, String spaceKey);
}
