package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;

/**
 * Port der Ablage. Ergebnisse liegen getrennt von Payload und Kopfdaten; Schreiben eines Ergebnisses ändert weder die
 * Seite noch andere Ergebnisse.
 */
public interface EnrichmentStore {

    /** Ersetzt das Ergebnis desselben Prozessors für dieselbe Seite. */
    void save(Enrichment enrichment);

    /** Alle Ergebnisse einer Seite, aufsteigend nach {@link Enrichment#processorId()}. */
    List<Enrichment> find(PageKey key);

    /** Entfernt alle Ergebnisse einer Seite, z. B. wenn die Seite aus dem Cache entfernt wurde. */
    void removeAll(PageKey key);

    /** Macht die bisherigen Änderungen dauerhaft, wenn die Ablage sie bündelt. */
    default void flush() {
    }
}
