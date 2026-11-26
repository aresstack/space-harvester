package com.aresstack.spaceharvester.application.jobs;

import java.time.Duration;
import java.util.Objects;

/**
 * Grenzen für Aufträge.
 *
 * @param maxQueued     höchstens wartende Aufträge; darüber hinaus wird ein neuer Auftrag abgelehnt
 * @param maxRunTime    Laufzeitgrenze je Auftrag ab Start; wird an jedem {@link JobContext#checkpoint()} geprüft
 * @param retention     wie lange ein beendeter Auftrag ab seinem Ende abrufbar bleibt
 * @param maxOpenPerOwner höchstens offene (laufende und wartende) Aufträge je Auslöser, damit ein Client nicht alle
 *                      Plätze belegt; interne Aufträge ohne Auslöser zählen nicht
 * @param retry         Wiederholungen bei vorübergehenden Fehlern und Selbst-Anhalten ({@code jobs.retry.*})
 * @param parallelPreparations „Vorbereitung“: höchstens gleichzeitig vorbereitende Downloads ({@link JobType#download()})
 *                      und ebenso viele übrige Aufträge (Abgleiche, Seitenexporte, Folgeaufträge; #944) ( *                      {@code jobs.parallel-preparations}); 1 bis {@value #MAX_PARALLEL_PREPARATIONS}
 * @param parallelDownloads höchstens gleichzeitig ladende Downloads ({@code jobs.parallel-downloads}); 0 bereitet nur
 *                      vor und lädt nichts; bis {@value #MAX_PARALLEL_DOWNLOADS}
 * @param downloadConnections gleichzeitige Verbindungen aller ladenden Downloads zusammen
 *                      ({@code jobs.download-connections}, Angelo 11.11.2026), nur Bilder und Anhänge, nach Restzeit
 *                      auf sie verteilt; 0 lädt keine Bilder und Anhänge; bis {@value #MAX_DOWNLOAD_CONNECTIONS}
 * @param maxOpenBackground höchstens zugleich offene Hintergrundaufträge ({@code jobs.max-open-background}, eigener
 *                      Vorrat neben dem der Benutzer); darüber wird ein weiterer abgelehnt, die Arbeit holt der nächste
 *                      Anlass nach
 * @param maxWaitingPrefetches höchstens wartende Anhänge je Quelle und Space beim Vorabladen
 *                      ({@code jobs.max-waiting-prefetches}, nur App); darüber lädt der Aufrufer wie bisher selbst
 */
public record JobLimits(int maxQueued, Duration maxRunTime, Duration retention,
        int maxOpenPerOwner, RetrySettings retry, int parallelPreparations, int parallelDownloads,
        int downloadConnections, int maxOpenBackground, int maxWaitingPrefetches) {

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
    /** Standard für {@link #maxQueued()} ({@code jobs.max-queued}). */
    public static final int DEFAULT_MAX_QUEUED = 20;
    /** Standard für {@link #maxRunTime()} ({@code jobs.max-run-time}). */
    public static final Duration DEFAULT_MAX_RUN_TIME = Duration.ofHours(6);
    /** Standard für {@link #maxOpenPerOwner()} im Dienst ({@code jobs.max-open-per-owner}). */
    public static final int DEFAULT_MAX_OPEN_PER_OWNER = 4;
    /** Standard für {@link #maxOpenBackground()}: je Space einer, also auch für viele Spaces genug. */
    public static final int DEFAULT_MAX_OPEN_BACKGROUND = 1000;
    /** Standard für {@link #maxWaitingPrefetches()}. */
    public static final int DEFAULT_MAX_WAITING_PREFETCHES = 5_000;
    /** Die Standardgrenzen der App (ein Bediener: die Grenze je Auslöser ist die Gesamtgrenze). */
    public static final JobLimits DEFAULTS = new JobLimits(DEFAULT_PARALLEL_PREPARATIONS, DEFAULT_MAX_QUEUED,
            DEFAULT_MAX_RUN_TIME);

    /*
     * #944 (Angelo 17.11.2026): keine festen Auftragsplätze. Alle Aufträge laufen nach „Vorbereitung“
     * (parallelPreparations) und „Downloads“; die kurzen Konstruktoren nehmen die Vorbereitung als ersten Wert.
     */

    public JobLimits(int parallelPreparations, int maxQueued, Duration maxRunTime) {
        this(parallelPreparations, maxQueued, maxRunTime, DEFAULT_RETENTION);
    }

    /** Ohne Grenze je Auslöser (nur die Gesamtgrenze). */
    public JobLimits(int parallelPreparations, int maxQueued, Duration maxRunTime, Duration retention) {
        this(parallelPreparations, maxQueued, maxRunTime, retention, parallelPreparations + maxQueued);
    }

    /** Mit den Standard-Wiederholungen ({@link RetrySettings#DEFAULT}). */
    public JobLimits(int parallelPreparations, int maxQueued, Duration maxRunTime, Duration retention,
                     int maxOpenPerOwner) {
        this(parallelPreparations, maxQueued, maxRunTime, retention, maxOpenPerOwner, RetrySettings.DEFAULT);
    }

    /** Mit den Standardgrenzen für Downloads und Verbindungen. */
    public JobLimits(int parallelPreparations, int maxQueued, Duration maxRunTime, Duration retention,
                     int maxOpenPerOwner, RetrySettings retry) {
        this(maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, parallelPreparations,
                DEFAULT_PARALLEL_DOWNLOADS, DEFAULT_DOWNLOAD_CONNECTIONS);
    }

    /** Mit dem Standard für {@link #maxOpenBackground()}. */
    public JobLimits(int maxQueued, Duration maxRunTime, Duration retention, int maxOpenPerOwner, RetrySettings retry,
                     int parallelPreparations, int parallelDownloads, int downloadConnections) {
        this(maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, parallelPreparations, parallelDownloads,
                downloadConnections, DEFAULT_MAX_OPEN_BACKGROUND, DEFAULT_MAX_WAITING_PREFETCHES);
    }

    /** Dieselben Grenzen mit einem anderen Vorrat für Hintergrundaufträge. */
    public JobLimits withBackground(int openBackground) {
        return new JobLimits(maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, parallelPreparations,
                parallelDownloads, downloadConnections, openBackground, maxWaitingPrefetches);
    }

    /** Dieselben Grenzen mit einer anderen Grenze wartender Anhänge beim Vorabladen. */
    public JobLimits withWaitingPrefetches(int waiting) {
        return new JobLimits(maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, parallelPreparations,
                parallelDownloads, downloadConnections, maxOpenBackground, waiting);
    }

    /** Dieselben Grenzen mit anderen Wiederholungen. */
    public JobLimits withRetry(RetrySettings value) {
        return new JobLimits(maxQueued, maxRunTime, retention, maxOpenPerOwner, value, parallelPreparations,
                parallelDownloads, downloadConnections, maxOpenBackground, maxWaitingPrefetches);
    }

    /** Dieselben Grenzen mit anderen Grenzen für Vorbereitung und Downloads. */
    public JobLimits withParallel(int preparations, int downloads) {
        return new JobLimits(maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, preparations, downloads,
                downloadConnections, maxOpenBackground, maxWaitingPrefetches);
    }

    /** Dieselben Grenzen mit einem anderen Gesamtbudget an Verbindungen. */
    public JobLimits withConnections(int connections) {
        return new JobLimits(maxQueued, maxRunTime, retention, maxOpenPerOwner, retry, parallelPreparations,
                parallelDownloads, connections, maxOpenBackground, maxWaitingPrefetches);
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
        if (maxOpenBackground < 1) {
            throw new IllegalArgumentException("max-open-background muss mindestens 1 sein");
        }
        if (maxWaitingPrefetches < 1) {
            throw new IllegalArgumentException("max-waiting-prefetches muss mindestens 1 sein");
        }
        requirePreparations(parallelPreparations);
        requireDownloads(parallelDownloads);
        requireConnections(downloadConnections);
    }
}
