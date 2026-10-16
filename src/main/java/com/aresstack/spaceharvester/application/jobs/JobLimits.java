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
 */
public record JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime, Duration retention) {

    /** Standard für {@link #retention()}. */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    public JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime) {
        this(maxConcurrent, maxQueued, maxRunTime, DEFAULT_RETENTION);
    }

    public JobLimits {
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
    }
}
