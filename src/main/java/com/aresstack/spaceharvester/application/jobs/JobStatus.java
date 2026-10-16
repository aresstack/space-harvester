package com.aresstack.spaceharvester.application.jobs;

import java.time.Instant;
import java.util.Objects;

/**
 * Momentaufnahme eines Auftrags.
 *
 * @param id              Job-ID
 * @param type            Art des Auftrags
 * @param sourceId        Quelle, auf die sich der Auftrag bezieht
 * @param partitionKey    Partition (Space) oder {@code null}, wenn der Auftrag die ganze Quelle betrifft
 * @param owner           auslösender Client ({@code principal} des Zugriffs, #14) oder {@code null} für interne
 *                        Aufträge wie die geplante Synchronisierung; nur er sieht den Auftrag über die API
 * @param state           aktueller Zustand
 * @param progress        Zähler
 * @param error           nur bei {@link JobState#FAILED}, sonst {@code null}
 * @param cancelRequested ein Abbruch wurde angefordert, der laufende Auftrag hat ihn aber noch nicht bemerkt
 * @param createdAt       Annahme des Auftrags
 * @param startedAt       Beginn der Ausführung oder {@code null}
 * @param finishedAt      Erreichen eines Endzustands oder {@code null}
 */
public record JobStatus(String id, JobType type, String sourceId, String partitionKey, String owner, JobState state,
                        JobProgress progress, JobError error, boolean cancelRequested, Instant createdAt,
                        Instant startedAt, Instant finishedAt) {

    public JobStatus {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(createdAt, "createdAt");
        if ((state == JobState.FAILED) != (error != null)) {
            throw new IllegalArgumentException("error genau dann, wenn state FAILED ist");
        }
    }
}
