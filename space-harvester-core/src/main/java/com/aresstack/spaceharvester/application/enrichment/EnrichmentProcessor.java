package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageKey;

/**
 * SPI eines deterministischen Prozessors. Gleiche Eingabe und gleiche Version ergeben dasselbe Ergebnis; ändert sich
 * die Logik, steigt {@link #version()}, und alle bisherigen Ergebnisse dieses Prozessors gelten als veraltet.
 */
public interface EnrichmentProcessor {

    /** Stabile Kennung, z. B. {@code acronyms}; Kleinbuchstaben, Ziffern und Bindestriche. */
    String id();

    /** Version der Logik, z. B. {@code 1}. */
    String version();

    /**
     * @return das Ergebnis; eine Ausnahme macht nur dieses Ergebnis fehlgeschlagen
     */
    EnrichmentOutput process(PageText text);

    /**
     * Wie {@link #process(PageText)}, mit der Seite, zu der der Text gehört; für Prozessoren, deren Verfahren von der
     * Quelle abhängt (etwa nur freigegebene Quellen an den KI-Dienst, ADR 0011). {@link Enrichments} ruft diese Form.
     */
    default EnrichmentOutput process(PageKey key, PageText text) {
        return process(text);
    }

    /**
     * Ob der Prozessor diese Seite bearbeitet. Ein Prozessor, der Inhalte an einen externen Dienst schickt, bearbeitet
     * nur Seiten aus dafür freigegebenen Quellen (ADR 0011); für alle anderen entsteht kein Ergebnis.
     */
    default boolean appliesTo(PageKey key) {
        return true;
    }

    /**
     * Ob der Prozessor über das Netz rechnet. Dann berechnet {@link Enrichments#refresh} ihn im Hintergrund, sofern
     * {@link Enrichments} einen Hintergrund hat: Lesen, Abgleich und Export warten nie auf ihn.
     */
    default boolean background() {
        return false;
    }
}
