package com.aresstack.spaceharvester.application.sync;

import java.util.Locale;

/**
 * Betriebsart eines Abgleichs (ADR 0014, Fortschreibung): was ein Lauf außer dem Katalog anfasst. Der Seitenkatalog
 * (Kopfdaten, Eltern, Version) und die Inhalte haben getrennte Lebenszyklen; Inhalte beschafft regulär die Suche.
 */
public enum SyncMode {

    /**
     * Nur katalogisieren (Standard für Zeitplan und Nachholen beim Start): Kopfdaten neuer und bekannter Seiten speichern oder aktualisieren, keine Inhalte laden. Eine
     * gecachte Seite mit neuer Version bleibt mit altem Stand und gilt als veraltet
     * ({@link PageChange.Kind#OUTDATED}); die Versionsprüfung der Suche lädt sie, sobald sie gebraucht wird.
     */
    CATALOG_ONLY,

    /**
     * Cache synchronisieren: unbekannte Seiten nur katalogisieren ({@link PageChange.Kind#NOT_LOADED}),
     * gecachte bei neuer Version neu laden.
     */
    SYNC_CACHE,

    /** Vollständig: auch die Inhalte unbekannter Seiten laden und den Space so ganz im Cache halten. */
    FULL;

    /** Für die bisherige Angabe {@code loadUnknown}: {@code true} ist {@link #FULL}, sonst {@link #SYNC_CACHE}. */
    public static SyncMode loadingUnknown(boolean loadUnknown) {
        return loadUnknown ? FULL : SYNC_CACHE;
    }

    /** Ob unbekannte Seiten mit Inhalt übernommen werden. */
    public boolean loadsUnknown() {
        return this == FULL;
    }

    /** Ob gecachte Seiten mit neuer Version neu geladen werden. */
    public boolean refreshesContent() {
        return this != CATALOG_ONLY;
    }

    /** Name in Konfiguration und REST: {@code catalog-only}, {@code sync-cache}, {@code full}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Wie {@link #value()}, für Properties und YAML. */
    public String key() {
        return value();
    }

    /** Liest {@link #value()} oder den Namen der Konstante, nachsichtig; {@code null}, wenn der Wert unbekannt ist. */
    public static SyncMode parse(String value) {
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (SyncMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * @throws IllegalArgumentException für einen unbekannten Namen
     */
    public static SyncMode of(String value) {
        for (SyncMode mode : values()) {
            if (mode.value().equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Betriebsart muss catalog-only, sync-cache oder full sein");
    }
}
