package com.aresstack.spaceharvester.application.quality;

/** Ob eine Seite lokal gespeichert ist (in der App über die Holdings des Core). */
@FunctionalInterface
public interface LocalHoldings {

    /** Unbekannt: zählt als nicht gespeichert. */
    LocalHoldings UNKNOWN = page -> false;

    boolean stored(PageRef page);
}
