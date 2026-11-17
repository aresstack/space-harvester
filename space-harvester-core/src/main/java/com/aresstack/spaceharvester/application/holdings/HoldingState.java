package com.aresstack.spaceharvester.application.holdings;

/** Zustand einer Seite im Bestand (ADR 0027, Abschnitt 4). */
public enum HoldingState {
    /** Nur im Katalog, kein Inhalt lokal. */
    REMOTE,
    /** Inhalt lokal und aktuell, gleich ob durch Laden, Lesen, Suche oder Hintergrundfüllung. */
    LOCAL,
    /**
     * Inhalt lokal, aber veraltet: Die aktuellen Kopfdaten nennen eine andere Version als der gespeicherte Inhalt, oder
     * die bestätigte Generation führt die Seite als veraltet ({@code stalePageIds}).
     */
    OUTDATED,
    /** Für das Konto nicht sichtbar (gesperrt, ADR 0014); der Inhalt wird nicht mehr ausgegeben. */
    LOCKED,
    /**
     * Nur in der Teilübersicht nach einer Meldung: Die genannte Seite gibt es in diesem Space nicht mehr (entfernt,
     * verschoben oder für den Aufrufer nicht sichtbar).
     */
    REMOVED
}
