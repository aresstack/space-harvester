package com.aresstack.spaceharvester.application.standing;

/**
 * Stand einer Aussage oder Seite (ADR 0032, FL6). Die Reihenfolge der Konstanten ist die Sortierung der Status-quo-Sicht:
 * Geltendes zuerst, Überholtes zuletzt (blass, nie versteckt).
 */
public enum Standing {
    /** Gilt jetzt. */
    GILT,
    /** Geplant oder beabsichtigt, gilt noch nicht. */
    GEPLANT,
    /** Nicht bestimmbar, etwa ohne Merkmale oder auf einer Entwurfsseite. */
    UNKLAR,
    /** Früher gültig oder verworfen. */
    UEBERHOLT
}
