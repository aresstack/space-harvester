package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.JobLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester.jobs}: Grenzen für asynchrone Aufträge (Ernte, Sync, Export).
 *
 * @param maxConcurrent höchstens gleichzeitig laufende Aufträge (Standard 2)
 * @param maxQueued     höchstens wartende Aufträge, darüber wird ein neuer abgelehnt (Standard 20)
 * @param maxRunTime    Laufzeitgrenze je Auftrag (Standard 6 Stunden)
 * @param retention     wie lange beendete Aufträge abrufbar bleiben (Standard 7 Tage)
 */
@ConfigurationProperties("space-harvester.jobs")
public record JobsProperties(Integer maxConcurrent, Integer maxQueued, Duration maxRunTime, Duration retention) {

    public JobsProperties {
        maxConcurrent = maxConcurrent == null ? 2 : maxConcurrent;
        maxQueued = maxQueued == null ? 20 : maxQueued;
        maxRunTime = maxRunTime == null ? Duration.ofHours(6) : maxRunTime;
        retention = retention == null ? JobLimits.DEFAULT_RETENTION : retention;
    }
}
