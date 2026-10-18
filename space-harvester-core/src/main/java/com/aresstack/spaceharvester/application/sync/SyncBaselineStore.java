package com.aresstack.spaceharvester.application.sync;

import java.util.Map;
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

    /**
     * Was der Cache von diesem Space kennt, auch ohne bestätigte Generation (ADR 0014, Punkt 2): je Seite, deren
     * aktuelle Kopfdaten im Space liegen und deren Snapshot gespeichert ist, der Stand dieses Snapshots. Der Abgleich
     * vergleicht die Inventur damit, statt Seiten neu zu laden, die durch Suche oder Abruf schon im Cache liegen.
     * Standard: nichts.
     */
    default Map<String, BaselineEntry> knownPages(String sourceId, String spaceKey) {
        return Map.of();
    }

    /** Beginnt die Schreibvorgänge eines Laufs für diesen Space; bis zum Commit für Leser unsichtbar. */
    StagedGeneration begin(String sourceId, String spaceKey);
}
