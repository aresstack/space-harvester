package com.aresstack.spaceharvester.application.configuration;

import java.util.Locale;

/**
 * Was ein Abgleichslauf aus Zeitplan oder Nachholen beim Start tut, unter {@code space-harvester.sync.mode}. Die
 * manuellen Aufträge „Initialisieren“ und „Bestand abgleichen“ sind keine Konfiguration.
 */
public enum SyncMode {

    /** Nur Metadaten in den Katalog, keine Inhalte (Standard). */
    CATALOG_ONLY,
    /** Unbekannte Seiten katalogisieren, gecachte Seiten per Version prüfen und bei Änderung neu laden. */
    SYNC_CACHE,
    /** Inhalte aller Seiten laden. */
    FULL;

    /** Wert in Properties und YAML, z. B. {@code catalog-only}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Liest {@link #key()} oder den Namen der Konstante; {@code null}, wenn der Wert unbekannt ist. */
    public static SyncMode parse(String value) {
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (SyncMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
