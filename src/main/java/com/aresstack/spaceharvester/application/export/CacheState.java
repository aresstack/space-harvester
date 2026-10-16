package com.aresstack.spaceharvester.application.export;

/** Stand der Cache-Generation, aus der exportiert wird; nur {@link #CONFIRMED} kann ein vollständiges Artefakt ergeben. */
public enum CacheState {
    /** Aus einem bestätigt vollständigen Abgleich mit der Quelle (#16). */
    CONFIRMED,
    /** Der letzte Abgleich war unvollständig oder lief noch. */
    INCOMPLETE,
    /** Bestätigt, aber die Quelle war seitdem nicht erreichbar oder der Stand ist zu alt. */
    STALE
}
