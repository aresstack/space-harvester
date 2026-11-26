package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.api.ProgressiveSearches;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Grenzen der progressiven Suchen der REST-API unter {@code space-harvester.search.progressive} (ADR 0017, F38-4,
 * docs/grenzen.md). Nur der Dienst kennt diese Suchen; die App sucht ohne dieses Register.
 *
 * @param maxRunning höchstens so viele Suchen laufen zugleich; eine weitere wird mit 429 abgewiesen, wartende Suchen
 *                   gibt es nicht (Standard {@value ProgressiveSearches#DEFAULT_MAX_RUNNING})
 * @param retention  so lange bleibt eine beendete Suche abrufbar (Standard 5 Minuten)
 */
@ConfigurationProperties("space-harvester.search.progressive")
public record ProgressiveSearchProperties(Integer maxRunning, Duration retention) {

    public ProgressiveSearchProperties {
        maxRunning = maxRunning == null ? ProgressiveSearches.DEFAULT_MAX_RUNNING : maxRunning;
        retention = retention == null ? ProgressiveSearches.DEFAULT_RETENTION : retention;
        if (maxRunning < 1) {
            throw new IllegalArgumentException("space-harvester.search.progressive.max-running muss mindestens 1 sein");
        }
        if (retention.isNegative()) {
            throw new IllegalArgumentException("space-harvester.search.progressive.retention darf nicht negativ sein");
        }
    }

    ProgressiveSearches.Limits toLimits() {
        return new ProgressiveSearches.Limits(maxRunning, retention);
    }
}
