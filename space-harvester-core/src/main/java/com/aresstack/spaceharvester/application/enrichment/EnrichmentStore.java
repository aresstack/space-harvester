package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
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

    /** Entfernt alle Ergebnisse einer Seite samt ihren Dokumenten, z. B. wenn die Seite aus dem Cache entfernt wurde. */
    void removeAll(PageKey key);

    /**
     * Entfernt das Ergebnis eines Prozessors für eine Seite samt Vektoren, Begriffen und Fakten, etwa wenn der Prozessor
     * die Seite nicht mehr bearbeitet (Freigabe entzogen, ADR 0015); ohne Ergebnis nichts.
     */
    void remove(PageKey key, String processorId);

    /**
     * Alle Ergebnisse eines Anhangs oder einer enthaltenen Datei (ADR 0026, Punkt 10), aufsteigend nach Abschnitt und
     * {@link Enrichment#processorId()}; für die Seite selbst wie {@link #find(PageKey)}. {@link #find(PageKey)} liefert
     * nie Ergebnisse von Dokumenten.
     */
    List<Enrichment> find(DocumentKey document);

    /**
     * Entfernt alle Ergebnisse eines Dokuments, aller Abschnitte und Prozessoren; für einen Anhang auch die seiner
     * enthaltenen Dateien. Für die Seite selbst nur ihre eigenen Ergebnisse (wie {@link #removeAll(PageKey)} ohne die
     * Dokumente). {@link #removeAll(PageKey)} entfernt die Seite samt allen ihren Dokumenten.
     */
    void removeDocument(DocumentKey document);

    /** Macht die bisherigen Änderungen dauerhaft, wenn die Ablage sie bündelt. */
    default void flush() {
    }
}
