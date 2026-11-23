package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobStore;
import com.aresstack.spaceharvester.application.jobs.JobStoreContract;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Vertrag aus Strang E, gegen die Lucene-Auftragsablage auf einem temporären Verzeichnis. */
class LuceneJobStoreContractTest extends JobStoreContract {

    @TempDir
    Path base;

    private final List<LuceneJobStore> opened = new ArrayList<>();

    @Override
    protected JobStore newStore() {
        return track(LuceneJobStore.open(base.resolve("jobs-" + opened.size())));
    }

    @Override
    protected JobStore reopen(JobStore store) {
        LuceneJobStore lucene = (LuceneJobStore) store;
        lucene.close();
        return track(LuceneJobStore.open(lucene.path()));
    }

    private LuceneJobStore track(LuceneJobStore store) {
        opened.add(store);
        return store;
    }

    @AfterEach
    void closeAll() {
        opened.forEach(LuceneJobStore::close);
    }

    @Test
    void closingAfterAnInterruptedSaveReleasesTheStoreWithoutThrowing() {
        // Roter develop-Build 22.11.2026: Ein Interrupt mitten in der Datei-Ein-/Ausgabe macht die Sperrdatei ungültig
        // und schließt den Writer; Schließen und Neustart müssen trotzdem gehen.
        LuceneJobStore store = (LuceneJobStore) newStore();
        JobStatus a = new JobStatus("a", JobType.HARVEST, "wiki", null, null, JobState.QUEUED, JobProgress.NONE,
                null, false, Instant.parse("2026-10-16T12:00:00Z"), null, null);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> store.save(a)).isInstanceOf(RuntimeException.class);
        } finally {
            Thread.interrupted();
        }

        assertThatCode(store::close).doesNotThrowAnyException();
        LuceneJobStore reopened = track(LuceneJobStore.open(store.path()));
        reopened.save(a);
        assertThat(reopened.find("a")).isPresent();
    }

    @Test
    void aDeletedJobStaysDeletedAfterARestart() {
        LuceneJobStore store = (LuceneJobStore) newStore();
        JobStatus a = new JobStatus("a", JobType.HARVEST, "wiki", null, null, JobState.COMPLETED, JobProgress.NONE,
                null, false, Instant.parse("2026-10-16T12:00:00Z"), null, Instant.parse("2026-10-16T12:01:00Z"));
        JobStatus b = new JobStatus("b", JobType.HARVEST, "wiki", "DEV", null, JobState.QUEUED, JobProgress.NONE,
                null, false, Instant.parse("2026-10-16T12:00:00Z"), null, null);
        store.save(a);
        store.save(b);

        store.delete("a");

        assertThat(reopen(store).all()).containsExactly(b);
    }

    @Test
    void aSecondProcessCannotOpenTheSameStore() {
        LuceneJobStore store = (LuceneJobStore) newStore();

        assertThatThrownBy(() -> LuceneJobStore.open(store.path())).isInstanceOf(PageCacheException.class)
                .hasMessageContaining("anderen Prozess");
    }

    @Test
    void thePageCacheIndexIsNotTakenForAJobStore() {
        Path pages = base.resolve("pages");
        LucenePageCache.open(pages).close();

        assertThatThrownBy(() -> LuceneJobStore.open(pages)).isInstanceOf(PageCacheException.class)
                .hasMessageContaining(PageCacheSchema.VERSION).hasMessageContaining(LuceneJobStore.VERSION);
    }

    @Test
    void aRequestedPauseAndItsReasonSurviveARestartSoTheNextStartKeepsTheUsersWish() {
        LuceneJobStore store = (LuceneJobStore) newStore();
        Instant t = Instant.parse("2026-11-21T19:00:00Z");
        store.save(new JobStatus("user", JobType.BACKGROUND, "wiki", "ALPHA", "app", JobState.RUNNING,
                JobProgress.NONE, null, true, t, t, null, null, null, java.util.Map.of(), false, true,
                com.aresstack.spaceharvester.application.jobs.PauseReason.USER));
        store.save(new JobStatus("retry", JobType.BACKGROUND, "wiki", "BETA", "app", JobState.RUNNING,
                JobProgress.NONE, null, true, t, t, null, null, null, java.util.Map.of(), false, true,
                com.aresstack.spaceharvester.application.jobs.PauseReason.RETRY));

        JobStore reopened = reopen(store);
        assertThat(reopened.find("user").orElseThrow().pauseRequested()).isTrue();
        assertThat(reopened.find("retry").orElseThrow().pauseReason())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.RETRY);

        // Der nächste Start (Jobs liest die Ablage): Nutzerpause bleibt angehalten, ↻ bleibt fortsetzbar.
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs
                .Jobs(reopened, Runnable::run, new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10,
                java.time.Duration.ofHours(1)), java.time.Clock.systemUTC());
        try {
            JobStatus user = jobs.status("user");
            assertThat(user.state()).isEqualTo(JobState.PAUSED);
            assertThat(user.pauseReason()).isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.USER);
            JobStatus retry = jobs.status("retry");
            assertThat(retry.state()).isEqualTo(JobState.PAUSED);
            assertThat(retry.pauseReason())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.RETRY);
        } finally {
            jobs.close();
        }
    }
}
