package com.aresstack.spaceharvester.deck.terminal;

/**
 * Lokale Bewegungen der Schreibmarke für den Maskenassistenten (#1151). Keine davon schickt etwas an den Host – auch
 * {@link #NEW_LINE} ist der lokale Zeilenwechsel des 3270 (New Line), nicht ENTER.
 */
public enum CursorMove {
    /** Ins nächste Eingabefeld (Tab), am Ende zurück zum ersten. */
    TAB,
    /** An den Anfang des aktuellen bzw. vorigen Eingabefeldes (Backtab). */
    BACKTAB,
    /** Ins erste Eingabefeld einer folgenden Zeile (New Line). */
    NEW_LINE,
    /** Ins erste Eingabefeld (Home). */
    HOME,
    /** An den Anfang eines bestimmten Eingabefeldes. */
    FIELD
}
