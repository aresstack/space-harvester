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
 * @param parallelPreparations Bilder- und Anhängeaufträge, die gleichzeitig vorbereiten (Standard 2, ADR 0025)
 * @param parallelDownloads    Bilder- und Anhängeaufträge, die gleichzeitig laden (Standard 4; 0 bereitet nur vor)
 */
@ConfigurationProperties("space-harvester.jobs")
public record JobsProperties(Integer maxConcurrent, Integer maxQueued, Duration maxRunTime, Duration retention,
        Integer maxOpenPerOwner, Retry retry, Integer parallelPreparations, Integer parallelDownloads) {

    public JobsProperties {
        maxConcurrent = maxConcurrent == null ? 2 : maxConcurrent;
        maxQueued = maxQueued == null ? 20 : maxQueued;
        maxRunTime = maxRunTime == null ? Duration.ofHours(6) : maxRunTime;
        retention = retention == null ? JobLimits.DEFAULT_RETENTION : retention;
        maxOpenPerOwner = maxOpenPerOwner == null ? 4 : maxOpenPerOwner;
        retry = retry == null ? new Retry(null, null, null, null, null, null) : retry;
        parallelPreparations = JobLimits.requirePreparations(parallelPreparations == null
                ? JobLimits.DEFAULT_PARALLEL_PREPARATIONS : parallelPreparations);
        parallelDownloads = JobLimits.requireDownloads(parallelDownloads == null
                ? JobLimits.DEFAULT_PARALLEL_DOWNLOADS : parallelDownloads);
    }

    /** Die Auftragsgrenzen für {@link com.aresstack.spaceharvester.application.jobs.Jobs}. */
    public JobLimits toLimits() {
        return new JobLimits(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, retry.toSettings(),
                parallelPreparations, parallelDownloads);
    }

    /**
     * {@code space-harvester.jobs.retry.*}; was fehlt, kommt aus {@link RetrySettings#DEFAULT}.
     *
     * @param maxAttempts        weitere Versuche nach dem ersten (0 bis 10, Standard 2)
     * @param initialDelay       erste Wartezeit (Standard 1 s)
     * @param maxDelay           längste Wartezeit (Standard 30 s)
     * @param pauseAfterFailures Netzfehler nacheinander bis zum Selbst-Anhalten (Standard 5)
     * @param resumeInitialDelay erste Wartezeit bis zum selbsttätigen Wiederanlauf (Standard 10 s)
     * @param resumeMaxDelay     längste Wartezeit bis zum Wiederanlauf (Standard 10 min)
     */
    public record Retry(Integer maxAttempts, Duration initialDelay, Duration maxDelay, Integer pauseAfterFailures,
            Duration resumeInitialDelay, Duration resumeMaxDelay) {

        /** @throws IllegalArgumentException bei Werten außerhalb der Grenzen von {@link RetrySettings} */
        public RetrySettings toSettings() {
            RetrySettings fallback = RetrySettings.DEFAULT;
            return new RetrySettings(maxAttempts == null ? fallback.maxAttempts() : maxAttempts,
                    initialDelay == null ? fallback.initialDelay() : initialDelay,
                    maxDelay == null ? fallback.maxDelay() : maxDelay,
                    pauseAfterFailures == null ? fallback.pauseAfterFailures() : pauseAfterFailures,
                    resumeInitialDelay == null ? fallback.resumeInitialDelay() : resumeInitialDelay,
                    resumeMaxDelay == null ? fallback.resumeMaxDelay() : resumeMaxDelay);
        }
    }
}
