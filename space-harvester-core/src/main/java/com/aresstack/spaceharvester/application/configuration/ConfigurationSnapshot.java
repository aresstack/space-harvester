package com.aresstack.spaceharvester.application.configuration;

import java.time.Instant;
import java.util.Objects;

/**
 * Eine aktivierte Konfiguration mit fortlaufender Version. Wer eine Aufgabe beginnt, nimmt sich den aktuellen
 * Schnappschuss und arbeitet bis zum Ende damit; ein späterer Wechsel ändert ihn nicht.
 *
 * @param version       1 für die Startkonfiguration, danach je Aktivierung um 1 höher
 * @param configuration die wirksame Konfiguration
 * @param activatedAt   Zeitpunkt der Aktivierung
 */
public record ConfigurationSnapshot(long version, HarvesterConfiguration configuration, Instant activatedAt) {

    public ConfigurationSnapshot {
        if (version < 1) {
            throw new IllegalArgumentException("version muss positiv sein");
        }
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }
}
