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
 */
public record JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime, Duration retention,
        int maxOpenPerOwner, RetrySettings retry) {

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

    /** Dieselben Grenzen mit anderen Wiederholungen. */
    public JobLimits withRetry(RetrySettings value) {
        return new JobLimits(maxConcurrent, maxQueued, maxRunTime, retention, maxOpenPerOwner, value);
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
    }
}
