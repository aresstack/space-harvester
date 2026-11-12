package com.aresstack.spaceharvester.application.jobs;

import java.time.Duration;
import java.util.Objects;

/**
 * Grenzen für Aufträge.
 *
 * @param maxConcurrent höchstens gleichzeitig laufende Aufträge (und damit gleichzeitige Quellzugriffe aus Aufträgen)
 * @param maxQueued     höchstens wartende Aufträge; darüber hinaus wird ein neuer Auftrag abgelehnt
 * @param maxRunTime    Laufzeitgrenze je Auftrag ab Start; wird an jedem {@link JobContext#checkpoint()} geprüft
 * @param retention     wie lange ein beendeter Auftrag ab seinem Ende abrufbar bleibt
 * @param maxOpenPerOwner höchstens offene (laufende und wartende) Aufträge je Auslöser, damit ein Client nicht alle
 *                      Plätze belegt; interne Aufträge ohne Auslöser zählen nicht
 * @param retry         Wiederholungen bei vorübergehenden Fehlern und Selbst-Anhalten ({@code jobs.retry.*})
 * @param parallelPreparations höchstens gleichzeitig vorbereitende Downloads ({@link JobType#download()},
 *                      {@code jobs.parallel-preparations}); 1 bis {@value #MAX_PARALLEL_PREPARATIONS}
 * @param parallelDownloads höchstens gleichzeitig ladende Downloads ({@code jobs.parallel-downloads}); 0 bereitet nur
 *                      vor und lädt nichts; bis {@value #MAX_PARALLEL_DOWNLOADS}
 * @param downloadConnections gleichzeitige Verbindungen aller ladenden Downloads zusammen
 *                      ({@code jobs.download-connections}, Angelo 11.11.2026), nur Bilder und Anhänge, nach Restzeit
 *                      auf sie verteilt; 0 lädt keine Bilder und Anhänge; bis {@value #MAX_DOWNLOAD_CONNECTIONS}
 */
public record JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime, Duration retention,
        int maxOpenPerOwner, RetrySettings retry, int parallelPreparations, int parallelDownloads,
        int downloadConnections) {

    public static final String PARALLEL_PREPARATIONS = "parallel-preparations";
    public static final String PARALLEL_DOWNLOADS = "parallel-downloads";
    public static final int DEFAULT_PARALLEL_PREPARATIONS = 2;
    public static final int DEFAULT_PARALLEL_DOWNLOADS = 4;
    public static final int MAX_PARALLEL_PREPARATIONS = 16;
    public static final int MAX_PARALLEL_DOWNLOADS = 64;
    public static final String DOWNLOAD_CONNECTIONS = "download-connections";
    public static final int DEFAULT_DOWNLOAD_CONNECTIONS = 8;
    public static final int MAX_DOWNLOAD_CONNECTIONS = 64;

    /** Standard für {@link #retention()}. */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    public JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime) {
        this(maxConcurrent, maxQueued, maxRunTime, DEFAULT_RETENTION);
    }

    /** Ohne Grenze je Auslöser (nur die Gesamtgrenze). */
    public JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime, Duration retention) {
        this(maxConcurrent, maxQueued, maxRunTime, retention, maxConcurrent + maxQueued);
    }

    /** Mit den Standard-Wiederholungen ({@link RetrySettings#DEFAULT}). */
    public JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime, Duration retention, int maxOpenPerOwner) {
        this(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, RetrySettings.DEFAULT);
    }

    /** Mit den Standardgrenzen für Downloads ({@link #DEFAULT_PARALLEL_DOWNLOADS}). */
    public JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime, Duration retention, int maxOpenPerOwner,
                     RetrySettings retry) {
        this(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, DEFAULT_PARALLEL_PREPARATIONS,
                DEFAULT_PARALLEL_DOWNLOADS);
    }

    /** Mit den Standardverbindungen je Download ({@link #DEFAULT_DOWNLOAD_CONNECTIONS}). */
    public JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime, Duration retention, int maxOpenPerOwner,
                     RetrySettings retry, int parallelPreparations, int parallelDownloads) {
        this(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, parallelPreparations,
                parallelDownloads, DEFAULT_DOWNLOAD_CONNECTIONS);
    }

    /** Dieselben Grenzen mit anderen Wiederholungen. */
    public JobLimits withRetry(RetrySettings value) {
        return new JobLimits(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, value,
                parallelPreparations, parallelDownloads, downloadConnections);
    }

    /** Dieselben Grenzen mit anderen Grenzen für Downloads. */
    public JobLimits withParallel(int preparations, int downloads) {
        return new JobLimits(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, preparations,
                downloads, downloadConnections);
    }

    /** Dieselben Grenzen mit einem anderen Gesamtbudget an Verbindungen. */
    public JobLimits withConnections(int connections) {
        return new JobLimits(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, retry,
                parallelPreparations, parallelDownloads, connections);
    }

    /** @throws IllegalArgumentException außerhalb von 0 bis {@value #MAX_DOWNLOAD_CONNECTIONS} */
    public static int requireConnections(int value) {
        if (value < 0 || value > MAX_DOWNLOAD_CONNECTIONS) {
            throw new IllegalArgumentException(DOWNLOAD_CONNECTIONS + " muss zwischen 0 und "
                    + MAX_DOWNLOAD_CONNECTIONS + " liegen");
        }
        return value;
    }

    /** @throws IllegalArgumentException außerhalb von 1 bis {@value #MAX_PARALLEL_PREPARATIONS} */
    public static int requirePreparations(int value) {
        if (value < 1 || value > MAX_PARALLEL_PREPARATIONS) {
            throw new IllegalArgumentException(PARALLEL_PREPARATIONS + " muss zwischen 1 und "
                    + MAX_PARALLEL_PREPARATIONS + " liegen");
        }
        return value;
    }

    /** @throws IllegalArgumentException außerhalb von 0 bis {@value #MAX_PARALLEL_DOWNLOADS} */
    public static int requireDownloads(int value) {
        if (value < 0 || value > MAX_PARALLEL_DOWNLOADS) {
            throw new IllegalArgumentException(PARALLEL_DOWNLOADS + " muss zwischen 0 und " + MAX_PARALLEL_DOWNLOADS
                    + " liegen");
        }
        return value;
    }

    public JobLimits {
        retry = retry == null ? RetrySettings.DEFAULT : retry;
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent muss mindestens 1 sein");
        }
        if (maxQueued < 0) {
            throw new IllegalArgumentException("maxQueued darf nicht negativ sein");
        }
        Objects.requireNonNull(maxRunTime, "maxRunTime");
        if (maxRunTime.isNegative() || maxRunTime.isZero()) {
            throw new IllegalArgumentException("maxRunTime muss positiv sein");
        }
        Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention muss positiv sein");
        }
        if (maxOpenPerOwner < 1) {
            throw new IllegalArgumentException("maxOpenPerOwner muss mindestens 1 sein");
        }
        requirePreparations(parallelPreparations);
        requireDownloads(parallelDownloads);
        requireConnections(downloadConnections);
    }
}
