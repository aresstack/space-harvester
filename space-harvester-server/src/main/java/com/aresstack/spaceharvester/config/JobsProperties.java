package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.RetrySettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester.jobs}: Grenzen für asynchrone Aufträge (Ernte, Sync, Export).
 *
 * @param maxConcurrent höchstens gleichzeitig laufende Aufträge (Standard 2)
 * @param maxQueued     höchstens wartende Aufträge, darüber wird ein neuer abgelehnt (Standard 20)
 * @param maxRunTime    Laufzeitgrenze je Auftrag (Standard 6 Stunden)
 * @param retention     wie lange beendete Aufträge abrufbar bleiben (Standard 7 Tage)
 * @param maxOpenPerOwner höchstens offene Aufträge je Client (Standard 4)
 * @param retry         Wiederholungen bei vorübergehenden Fehlern ({@code space-harvester.jobs.retry.*}, ADR 0025)
 */
@ConfigurationProperties("space-harvester.jobs")
public record JobsProperties(Integer maxConcurrent, Integer maxQueued, Duration maxRunTime, Duration retention,
        Integer maxOpenPerOwner, Retry retry) {

    public JobsProperties {
        maxConcurrent = maxConcurrent == null ? 2 : maxConcurrent;
        maxQueued = maxQueued == null ? 20 : maxQueued;
        maxRunTime = maxRunTime == null ? Duration.ofHours(6) : maxRunTime;
        retention = retention == null ? JobLimits.DEFAULT_RETENTION : retention;
        maxOpenPerOwner = maxOpenPerOwner == null ? 4 : maxOpenPerOwner;
        retry = retry == null ? new Retry(null, null, null, null) : retry;
    }

    /**
     * {@code space-harvester.jobs.retry.*}; was fehlt, kommt aus {@link RetrySettings#DEFAULT}.
     *
     * @param maxAttempts        weitere Versuche nach dem ersten (0 bis 10, Standard 2)
     * @param initialDelay       erste Wartezeit (Standard 1 s)
     * @param maxDelay           längste Wartezeit (Standard 30 s)
     * @param pauseAfterFailures Netzfehler nacheinander bis zum Selbst-Anhalten (Standard 5)
     */
    public record Retry(Integer maxAttempts, Duration initialDelay, Duration maxDelay, Integer pauseAfterFailures) {

        /** @throws IllegalArgumentException bei Werten außerhalb der Grenzen von {@link RetrySettings} */
        public RetrySettings toSettings() {
            RetrySettings fallback = RetrySettings.DEFAULT;
            return new RetrySettings(maxAttempts == null ? fallback.maxAttempts() : maxAttempts,
                    initialDelay == null ? fallback.initialDelay() : initialDelay,
                    maxDelay == null ? fallback.maxDelay() : maxDelay,
                    pauseAfterFailures == null ? fallback.pauseAfterFailures() : pauseAfterFailures);
        }
    }
}
