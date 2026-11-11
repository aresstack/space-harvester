package com.aresstack.spaceharvester.application.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
 * @param rootPageId      Startseite, wenn der Auftrag nur eine Seite mit ihren Unterseiten betrifft (Export eines
 *                        Teilbaums), sonst {@code null} für den ganzen Space; ältere abgelegte Aufträge kennen das Feld
 *                        nicht und lesen sich mit {@code null}
 * @param rootTitle       Titel der Startseite, sobald der Auftrag sie gelesen hat und der Auftraggeber sie lesen darf,
 *                        sonst {@code null}; für die Anzeige „Space &gt; Seite“
 * @param parameters      Angaben, mit denen sich der Auftrag nach einem Neustart gleichwertig neu anlegen lässt (etwa
 *                        Tiefe, Formate, Aktualität eines Exports oder der Export eines Bilderauftrags); die Schlüssel
 *                        legt der jeweilige Anwendungsfall fest. Nie Geheimnisse, Seiteninhalte oder Suchtexte. Ältere
 *                        abgelegte Aufträge lesen sich mit einer leeren Tabelle
 * @param incomplete      der Auftrag ist technisch beendet ({@link JobState#COMPLETED}), sein Ergebnis aber
 *                        unvollständig, etwa ein Export mit fehlenden Seiten oder Bilder ohne vollständiges Manifest
 * @param pauseRequested  mit {@code cancelRequested}: der Benutzer hält an, statt abzubrechen; nur solange der Auftrag
 *                        noch läuft, nie abgelegt
 * @param stage           nur bei {@link JobState#RUNNING} eines Downloads ({@link JobType#download()}): Vorbereitung,
 *                        vorbereitet oder Download, sonst {@code null}; wird nicht abgelegt
 * @param pauseReason     nur bei {@link JobState#PAUSED}: warum angehalten, sonst {@code null}; ältere abgelegte
 *                        Aufträge lesen sich mit {@link PauseReason#USER}
 */
public record JobStatus(String id, JobType type, String sourceId, String partitionKey, String owner, JobState state,
                        JobProgress progress, JobError error, boolean cancelRequested, Instant createdAt,
                        Instant startedAt, Instant finishedAt, String rootPageId, String rootTitle,
                        Map<String, String> parameters, boolean incomplete, boolean pauseRequested,
                        PauseReason pauseReason, JobStage stage) {

    /** Ohne Abschnitt ({@link #stage()}). */
    public JobStatus(String id, JobType type, String sourceId, String partitionKey, String owner, JobState state,
                     JobProgress progress, JobError error, boolean cancelRequested, Instant createdAt,
                     Instant startedAt, Instant finishedAt, String rootPageId, String rootTitle,
                     Map<String, String> parameters, boolean incomplete, boolean pauseRequested,
                     PauseReason pauseReason) {
        this(id, type, sourceId, partitionKey, owner, state, progress, error, cancelRequested, createdAt, startedAt,
                finishedAt, rootPageId, rootTitle, parameters, incomplete, pauseRequested, pauseReason, null);
    }

    public JobStatus {
        parameters = parameters == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(parameters));
        if (state != JobState.RUNNING) {
            stage = null;
        }
        if (state != JobState.PAUSED) {
            pauseReason = null;
        } else if (pauseReason == null) {
            pauseReason = PauseReason.USER;
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(createdAt, "createdAt");
        if ((state == JobState.FAILED) != (error != null)) {
            throw new IllegalArgumentException("error genau dann, wenn state FAILED ist");
        }
        if (rootPageId == null && rootTitle != null) {
            throw new IllegalArgumentException("rootTitle nur zusammen mit rootPageId");
        }
    }

    /** Ein Auftrag ohne Wiederanlauf-Angaben und mit vollständigem Ergebnis, sobald er fertig ist. */
    public JobStatus(String id, JobType type, String sourceId, String partitionKey, String owner, JobState state,
                     JobProgress progress, JobError error, boolean cancelRequested, Instant createdAt,
                     Instant startedAt, Instant finishedAt, String rootPageId, String rootTitle) {
        this(id, type, sourceId, partitionKey, owner, state, progress, error, cancelRequested, createdAt, startedAt,
                finishedAt, rootPageId, rootTitle, Map.of(), false, false, null);
    }

    /** Ein Auftrag ohne Startseite: ganzer Space oder ganze Quelle. */
    public JobStatus(String id, JobType type, String sourceId, String partitionKey, String owner, JobState state,
                     JobProgress progress, JobError error, boolean cancelRequested, Instant createdAt,
                     Instant startedAt, Instant finishedAt) {
        this(id, type, sourceId, partitionKey, owner, state, progress, error, cancelRequested, createdAt, startedAt,
                finishedAt, null, null);
    }

    /** Der wirksame Zustand mit den jetzt erlaubten Aktionen. */
    public JobLifecycleState lifecycle() {
        return JobLifecycleState.of(this);
    }

    /** Eine Wiederanlauf-Angabe ({@link #parameters()}) oder {@code null}. */
    public String parameter(String key) {
        return parameters.get(key);
    }

    /** Derselbe Zustand mit anderem Endzustand und Ende; für Aufträge, die ein Neustart unterbrochen hat. */
    JobStatus interrupted(JobError failure, Instant at) {
        return new JobStatus(id, type, sourceId, partitionKey, owner, JobState.FAILED, progress, failure,
                cancelRequested, createdAt, startedAt, at, rootPageId, rootTitle, parameters, false, false, null);
    }
}
