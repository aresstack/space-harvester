package com.aresstack.spaceharvester.application.facts;

/**
 * Ob eine Person den Fakt geprüft hat (wie {@code FactReview} im Enterprise AI Client). Die Ablage setzt von sich aus
 * nie etwas anderes als {@link #UNREVIEWED}; es gibt keine stillschweigende Bestätigung.
 */
public enum FactReview {
    UNREVIEWED,
    CONFIRMED,
    REJECTED
}
