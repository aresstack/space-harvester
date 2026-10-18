package com.aresstack.spaceharvester.application.sync;

import java.util.List;
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

    /**
     * Wie viele Seiten der Katalog in diesem Space kennt, mit oder ohne Inhalt, nach aktuellen Kopfdaten. Standard: die
     * Seiten mit Inhalt ({@link #knownPages}).
     */
    default int catalogedPages(String sourceId, String spaceKey) {
        return knownPages(sourceId, spaceKey).size();
    }

    /**
     * Alle Spaces mit bestätigter Generation, je {@code {sourceId, spaceKey}}; Ausgangspunkt für das Nachholen beim Start
     * (ADR 0014). Standard: keine.
     */
    default List<SpaceRef> confirmedSpaces() {
        return List.of();
    }

    /** Ein Space einer Quelle. */
    record SpaceRef(String sourceId, String spaceKey) {
    }

    /** Beginnt die Schreibvorgänge eines Laufs für diesen Space; bis zum Commit für Leser unsichtbar. */
    StagedGeneration begin(String sourceId, String spaceKey);
}
