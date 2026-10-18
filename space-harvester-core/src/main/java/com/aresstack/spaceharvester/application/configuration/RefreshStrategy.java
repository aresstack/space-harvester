package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.query.CacheFreshness;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Wie eine Seite im Cache aktuell bleibt, wenn sie gelesen, gefunden oder exportiert wird: die drei Wahlmöglichkeiten
 * der Einstellungen, unter {@code space-harvester.cache.refresh-strategy}. Sie ist eine Darstellung von
 * {@link CacheFreshness}; Änderungsquellen wie Webhooks oder ein Zeitplan ({@link SyncSettings}) kommen hinzu, sind
 * aber nie Voraussetzung.
 */
public enum RefreshStrategy {

    /** Bei jedem Abruf die Version bei der Quelle prüfen und nur eine neuere laden (Standard). */
    ALWAYS_CHECK_VERSION,
    /** Innerhalb von {@code cache.max-age} ohne Rückfrage aus dem Cache, danach neu abrufen. */
    TIME_BASED,
    /** Innerhalb von {@code cache.max-age} ohne Rückfrage, danach erst die Version prüfen. */
    TIME_BASED_WITH_VERSION_CHECK;

    /** Wert in Properties und YAML, z. B. {@code time-based-with-version-check}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Liest {@link #key()} oder den Namen der Konstante; {@code null}, wenn der Wert unbekannt ist. */
    public static RefreshStrategy parse(String value) {
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (RefreshStrategy strategy : values()) {
            if (strategy.name().equals(normalized)) {
                return strategy;
            }
        }
        return null;
    }

    /** Die Strategie zu einer Frische. */
    public static RefreshStrategy of(CacheFreshness freshness) {
        Objects.requireNonNull(freshness, "freshness");
        if (freshness.maxAge().isZero()) {
            return ALWAYS_CHECK_VERSION;
        }
        return freshness.versionCheck() ? TIME_BASED_WITH_VERSION_CHECK : TIME_BASED;
    }

    /**
     * Die Frische zu dieser Strategie.
     *
     * @param maxAge Zeitfenster der zeitbasierten Strategien; bei {@link #ALWAYS_CHECK_VERSION} ohne Bedeutung
     * @throws IllegalArgumentException wenn eine zeitbasierte Strategie kein positives Zeitfenster bekommt
     */
    public CacheFreshness freshness(Duration maxAge) {
        if (this == ALWAYS_CHECK_VERSION) {
            return CacheFreshness.DEFAULT;
        }
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("Eine zeitbasierte Aktualisierung braucht ein positives Zeitfenster");
        }
        return new CacheFreshness(maxAge, this == TIME_BASED_WITH_VERSION_CHECK);
    }
}
