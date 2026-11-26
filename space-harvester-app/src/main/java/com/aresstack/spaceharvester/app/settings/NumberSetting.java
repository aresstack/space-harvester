package com.aresstack.spaceharvester.app.settings;

/**
 * Ein einstellbarer ganzzahliger Wert mit Schlüssel, Bezeichnung, Hinweis, Vorgabe und Grenzen. Die Bereiche der
 * Einstellungen zeigen solche Werte einheitlich als Feld mit Hinweis ({@code NumberSettingsPanel}); Grenzen werden
 * so nie zu Magic Numbers im Code (Angelo 26.11.2026).
 */
public interface NumberSetting {

    String key();

    String label();

    String hint();

    long defaultValue();

    long min();

    long max();

    /** Ob {@code value} in den Grenzen liegt. */
    default boolean accepts(long value) {
        return value >= min() && value <= max();
    }
}
