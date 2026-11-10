package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port für die bestätigten Generationen je Space. Umsetzung im Lucene-Cache (#26); hier nur der Vertrag, den die
 * Synchronisierung braucht.
 *
 * <p>Alle Schreibvorgänge eines Laufs gehen über eine {@link StagedGeneration} und werden erst mit
 * {@link StagedGeneration#commit} sichtbar, zusammen mit der neuen Generation. Leser des Caches sehen daher nie
 * Änderungen eines Laufs, dessen Generation nicht bestätigt ist. Einzige Ausnahme sind neue, bisher unbekannte Seiten
 * über {@link #keepLoaded} (ADR 0025): Sie erscheinen wie ein Abruf schon vor der Generation, damit ein abgebrochener
 * Lauf fortgesetzt statt neu begonnen wird.
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

    /**
     * Ob {@link #keepLoaded} neue Seiten sofort ablegen kann (ADR 0025). Ohne das merkt ein Lauf alles in der
     * {@link StagedGeneration} vor, und ein Abbruch verwirft auch bereits geladene neue Seiten. Standard: nein.
     */
    default boolean keepsLoadedPages() {
        return false;
    }

    /**
     * Legt den Inhalt einer Seite, die weder Generation noch Cache kennen, sofort ab wie ein Abruf (ADR 0014, Punkt 2;
     * ADR 0025): ohne Generation, aber dauerhaft. Bricht der Lauf danach ab, kennt der nächste sie über
     * {@link #knownPages} und lädt sie nicht erneut. Nie für Änderungen bekannter Seiten, die bleiben atomar.
     *
     * @return ob abgelegt wurde; sonst merkt der Lauf die Seite wie bisher vor
     */
    default boolean keepLoaded(PageSnapshot snapshot) {
        return false;
    }

    /** Beginnt die Schreibvorgänge eines Laufs für diesen Space; bis zum Commit für Leser unsichtbar. */
    StagedGeneration begin(String sourceId, String spaceKey);
}
