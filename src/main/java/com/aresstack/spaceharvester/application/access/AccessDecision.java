package com.aresstack.spaceharvester.application.access;

/** Ergebnis einer {@link AccessPolicy}. Nur {@link #ALLOW} gibt frei. */
public enum AccessDecision {
    ALLOW,
    DENY,
    /**
     * Die Richtlinie kann nicht sicher entscheiden, etwa weil der Space einer Seite unbekannt ist, die delegierte Prüfung
     * bei der Quelle ausfiel oder unklar antwortete. Wird wie {@link #DENY} behandelt.
     */
    UNDETERMINED
}
