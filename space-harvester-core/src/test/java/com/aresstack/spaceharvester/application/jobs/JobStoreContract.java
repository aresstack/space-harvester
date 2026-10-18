package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Vertragstest für {@link JobStore}: jede Umsetzung erbt ihn und liefert in {@link #newStore()} eine leere Ablage.
 * Persistente Umsetzungen überschreiben zusätzlich {@link #reopen(JobStore)}, damit der Neustart geprüft wird.
 */
public abstract class JobStoreContract {

    private static final Instant T0 = Instant.parse("2026-10-16T12:00:00Z");

    /** Eine leere Ablage. */
    protected abstract JobStore newStore();

    /**
     * Dieselbe Ablage nach einem Neustart des Dienstes. Flüchtige Umsetzungen geben {@code null} zurück; dann entfällt
     * die Neustartprüfung.
     */
    protected JobStore reopen(JobStore store) {
        return null;
    }

    private static JobStatus queued(String id) {
        return new JobStatus(id, JobType.EXPORT, "confluence", "DEV", "TECHNICAL_CLIENT:etl", JobState.QUEUED,
                JobProgress.NONE, null, false, T0, null, null);
    }

    private static JobStatus failed(String id) {
        return new JobStatus(id, JobType.SYNC, "confluence", null, null, JobState.FAILED,
                new JobProgress(10, 7, 6, 1), new JobError("SOURCE_UNAVAILABLE", "Quelle nicht erreichbar"), true, T0,
                T0.plusSeconds(1), T0.plusSeconds(90));
    }

    @Test
    void unknownJobIsEmpty() {
        assertThat(newStore().find("nope")).isEmpty();
        assertThat(newStore().all()).isEmpty();
    }

    @Test
    void savedStatusComesBackUnchangedWithAllFields() {
        JobStore store = newStore();
        JobStatus status = failed("a");

        store.save(status);

        assertThat(store.find("a")).contains(status);
        assertThat(store.all()).containsExactly(status);
    }

    @Test
    void startPageOfASubtreeExportComesBack() {
        JobStore store = newStore();
        JobStatus status = new JobStatus("a", JobType.EXPORT, "confluence", "DEV", "TECHNICAL_CLIENT:etl",
                JobState.COMPLETED, new JobProgress(2, 2, 2, 0), null, false, T0, T0.plusSeconds(1),
                T0.plusSeconds(2), "100", "Seite 100");

        store.save(status);

        assertThat(store.find("a")).contains(status);
        // Ohne Startseite (auch Einträge aus der Zeit vor Teilbaum-Exporten) bleiben beide Felder leer.
        store.save(queued("b"));
        assertThat(store.find("b")).get().extracting(JobStatus::rootPageId, JobStatus::rootTitle)
                .containsExactly(null, null);
    }

    @Test
    void saveReplacesThePreviousStatusOfTheSameJob() {
        JobStore store = newStore();
        store.save(queued("a"));
        JobStatus running = new JobStatus("a", JobType.EXPORT, "confluence", "DEV", "TECHNICAL_CLIENT:etl",
                JobState.RUNNING, new JobProgress(3, 1, 1, 0), null, true, T0, T0.plusSeconds(1), null);

        store.save(running);

        assertThat(store.find("a")).contains(running);
        assertThat(store.all()).containsExactly(running);
    }

    @Test
    void jobsAreKeptApart() {
        JobStore store = newStore();
        store.save(queued("a"));
        store.save(failed("b"));

        assertThat(store.all()).containsExactlyInAnyOrder(queued("a"), failed("b"));
        assertThat(store.find("b")).contains(failed("b"));
    }

    @Test
    void deleteRemovesOnlyThatJob() {
        JobStore store = newStore();
        store.save(queued("a"));
        store.save(failed("b"));

        store.delete("b");
        store.delete("unknown");

        assertThat(store.find("b")).isEmpty();
        assertThat(store.all()).containsExactly(queued("a"));
    }

    @Test
    void savedJobsSurviveARestart() {
        JobStore store = newStore();
        store.save(queued("a"));
        store.save(failed("b"));

        JobStore reopened = reopen(store);

        if (reopened != null) {
            assertThat(reopened.all()).containsExactlyInAnyOrder(queued("a"), failed("b"));
        }
    }
}
