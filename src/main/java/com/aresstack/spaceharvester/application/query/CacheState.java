package com.aresstack.spaceharvester.application.query;

/** Zustand eines gecachten Space-Bestands (Cache-Generation), wie ihn die Synchronisierung (#16) festlegt. */
public enum CacheState {
    /** Aus einem vollständig abgeschlossenen Abgleich; nur hieraus dürfen Löschungen abgeleitet werden. */
    CONFIRMED,
    /** Ein Abgleich ist abgebrochen oder läuft noch; der Bestand kann Seiten fehlen lassen oder veraltete enthalten. */
    INCOMPLETE,
    /** Ein früher bestätigter Bestand, dessen Prüfung gegen die Quelle fehlschlug oder überfällig ist. */
    STALE
}
