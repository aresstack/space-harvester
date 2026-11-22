package com.aresstack.spaceharvester.application.background.services;

/**
 * Starttyp eines Analysedienstes, wie bei Windows-Diensten (D1, Nachtrag ADR 0025).
 */
public enum ServiceStart {
    /** Startet zusätzlich von selbst an seinen bisherigen Auslösern; ausdrücklich gestartet wie {@link #MANUAL}. */
    AUTOMATIC,
    /** Läuft, wenn er ausdrücklich gestartet wird oder ein ausdrücklich gestartetes Ziel ihn als Vorstufe braucht. */
    MANUAL,
    /** Startet nie; als Vorstufe gelb als „bewusst ausgelassen“ gemeldet. */
    DISABLED
}
