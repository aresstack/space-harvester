package com.aresstack.spaceharvester.application.enrichment;

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
}
