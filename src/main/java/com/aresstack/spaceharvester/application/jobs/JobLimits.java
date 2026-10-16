package com.aresstack.spaceharvester.application.jobs;

import java.time.Duration;
import java.util.Objects;

/**
 * Grenzen für Aufträge.
 *
 * @param maxConcurrent höchstens gleichzeitig laufende Aufträge (und damit gleichzeitige Quellzugriffe aus Aufträgen)
 * @param maxQueued     höchstens wartende Aufträge; darüber hinaus wird ein neuer Auftrag abgelehnt
 * @param maxRunTime    Laufzeitgrenze je Auftrag ab Start; wird an jedem {@link JobContext#checkpoint()} geprüft
 */
public record JobLimits(int maxConcurrent, int maxQueued, Duration maxRunTime) {

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
    }
}
