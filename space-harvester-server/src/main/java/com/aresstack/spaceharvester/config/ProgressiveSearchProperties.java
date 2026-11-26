package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.api.ProgressiveSearches;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Grenzen der progressiven Suchen der REST-API unter {@code space-harvester.search.progressive} (ADR 0017, F38-4,
 * docs/grenzen.md). Nur der Dienst kennt diese Suchen; die App sucht ohne dieses Register.
 *
 * @param maxRunning    höchstens so viele Suchen laufen zugleich (Standard
 *                      {@value ProgressiveSearches#DEFAULT_MAX_RUNNING})
 * @param maxWaiting    höchstens so viele warten dazu auf einen Platz; darüber 429 (Standard
 *                      {@value ProgressiveSearches#DEFAULT_MAX_WAITING}, {@code 0}: keine wartet)
 * @param retention     so lange bleibt eine beendete Suche abrufbar (Standard 5 Minuten)
 * @param maxTimeBudget höchstes Zeitbudget, das eine Anfrage verlangen darf (Standard 2 Minuten)
 */
@ConfigurationProperties("space-harvester.search.progressive")
public record ProgressiveSearchProperties(Integer maxRunning, Integer maxWaiting, Duration retention,
                                          Duration maxTimeBudget) {

    public ProgressiveSearchProperties {
        maxRunning = maxRunning == null ? ProgressiveSearches.DEFAULT_MAX_RUNNING : maxRunning;
        maxWaiting = maxWaiting == null ? ProgressiveSearches.DEFAULT_MAX_WAITING : maxWaiting;
        retention = retention == null ? ProgressiveSearches.DEFAULT_RETENTION : retention;
        maxTimeBudget = maxTimeBudget == null ? ProgressiveSearches.DEFAULT_MAX_TIME_BUDGET : maxTimeBudget;
        if (maxWaiting < 0) {
            throw new IllegalArgumentException("space-harvester.search.progressive.max-waiting darf nicht negativ sein");
        }
        if (maxTimeBudget.isNegative() || maxTimeBudget.isZero()) {
            throw new IllegalArgumentException("space-harvester.search.progressive.max-time-budget muss positiv sein");
        }
        if (maxRunning < 1) {
            throw new IllegalArgumentException("space-harvester.search.progressive.max-running muss mindestens 1 sein");
        }
        if (retention.isNegative()) {
            throw new IllegalArgumentException("space-harvester.search.progressive.retention darf nicht negativ sein");
        }
    }

    ProgressiveSearches.Limits toLimits() {
        return new ProgressiveSearches.Limits(maxRunning, maxWaiting, retention, maxTimeBudget);
    }
}
