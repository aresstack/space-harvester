package com.aresstack.spaceharvester.application.facts;

/** Wer einen Fakt erzeugt hat (wie {@code FactOrigin} im Enterprise AI Client). */
public enum FactOrigin {
    /** Aus dem Ergebnis eines Prozessors abgeleitet; wird mit ihm ersetzt. */
    AUTOMATIC,
    /** Von einer Person angelegt; kein Prozessorlauf berührt ihn. */
    MANUAL
}
