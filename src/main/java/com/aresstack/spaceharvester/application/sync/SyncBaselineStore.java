package com.aresstack.spaceharvester.application.sync;

import java.util.Optional;

/**
 * Port für die bestätigten Generationen je Space. Umsetzung im Lucene-Cache (#26); hier nur der Vertrag, den die
 * Synchronisierung braucht.
 */
public interface SyncBaselineStore {

    /** Die zuletzt bestätigte Generation oder leer, wenn der Space noch nie vollständig abgeglichen wurde. */
    Optional<SyncBaseline> confirmed(String sourceId, String spaceKey);

    /**
     * Veröffentlicht die nächste Generation als Ganzes: Leser sehen entweder die alte oder die neue, nie eine Mischung.
     *
     * @throws SyncConflictException wenn {@code next.generation()} nicht genau um eins über der aktuell bestätigten
     *                               liegt (bzw. nicht 1 ist, solange keine existiert), etwa weil ein paralleler Lauf
     *                               schneller war
     */
    void publish(SyncBaseline next);
}
