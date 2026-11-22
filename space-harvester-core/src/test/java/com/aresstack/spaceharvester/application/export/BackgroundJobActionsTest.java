package com.aresstack.spaceharvester.application.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.jobcontrol.JobActions;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobLifecycleState;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Fortsetzen und ↻ am Hintergrundteil eines Benutzers über {@link JobActions}: nur durch ihn selbst (Abnahme #1112),
 * auch das ↻, das ein laufendes Anhalten nur vormerkt. Nur erfundene Benutzer.
 */
class BackgroundJobActionsTest {

    private static final String OWNER = "app";
    private static final AccessContext ANNA = AccessContext.technicalClient("anna");
    private static final AccessContext BERT = AccessContext.technicalClient("bert");

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T17:00:00Z"), ZoneOffset.UTC);

    @Test
    void aRetryWhilePausingIsRejectedForAnotherUserAndAcceptedForTheStarter() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Jobs jobs = new Jobs(new InMemoryJobStore(), pool, new JobLimits(4, 10, Duration.ofHours(1)), clock);
            BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
            background.register(new BackgroundKind("demo", part -> JobTask.blocking(context -> {
                running.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })));
            JobActions actions = new JobActions(jobs,
                    new Exports(jobs, new InMemoryPageCache(), new RecordingExportArtifactWriter(), clock), null, null);
            actions.background(background);
            String groupId = background.start("demo", "Demo", List.of(new BackgroundPart.Draft("wiki", "DEV",
                    Map.of("owner", ANNA.jobOwner())))).groupId();
            String partId = background.group(groupId).orElseThrow().parts().getFirst().id();
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

            background.pause(groupId);
            assertThat(jobs.status(partId, OWNER).lifecycle())
                    .isInstanceOfSatisfying(JobLifecycleState.Stopping.class,
                            stopping -> assertThat(stopping.pausing()).isTrue());

            assertThatThrownBy(() -> actions.perform(partId, OWNER, JobAction.RETRY_FAILED, UnaryOperator.identity(),
                    BERT)).isInstanceOf(JobNotResumableException.class).hasMessageContaining("nur der Benutzer");
            assertThat(jobs.status(partId, OWNER).lifecycle()).as("kein Neustart vorgemerkt")
                    .isInstanceOf(JobLifecycleState.Stopping.class);

            // Der Eigentümer darf: das ↻ wird vorgemerkt, ohne Fehler.
            assertThat(actions.perform(partId, OWNER, JobAction.RETRY_FAILED, UnaryOperator.identity(), ANNA).id())
                    .isEqualTo(partId);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void resumingThePausedPartOfAUserIsRejectedForAnotherUser() {
        {
            Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)),
                    clock);
            BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
            java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
            background.register(new BackgroundKind("demo", part -> JobTask.blocking(context -> {
                if (runs.incrementAndGet() == 1) {
                    context.pause(com.aresstack.spaceharvester.application.jobs.PauseReason.USER);
                }
            })));
            JobActions actions = new JobActions(jobs,
                    new Exports(jobs, new InMemoryPageCache(), new RecordingExportArtifactWriter(), clock), null, null);
            actions.background(background);
            String groupId = background.start("demo", "Demo", List.of(new BackgroundPart.Draft("wiki", "DEV",
                    Map.of("owner", ANNA.jobOwner())))).groupId();
            String partId = background.group(groupId).orElseThrow().parts().getFirst().id();
            assertThat(jobs.status(partId, OWNER).state()).isEqualTo(JobState.PAUSED);

            assertThatThrownBy(() -> actions.perform(partId, OWNER, JobAction.RESUME, UnaryOperator.identity(), BERT))
                    .isInstanceOf(JobNotResumableException.class);
            background.resume(groupId, BERT);
            assertThat(jobs.status(partId, OWNER).state()).as("auch die Gruppe nicht").isEqualTo(JobState.PAUSED);
            assertThat(runs).hasValue(1);

            actions.perform(partId, OWNER, JobAction.RESUME, UnaryOperator.identity(), ANNA);
            assertThat(jobs.status(partId, OWNER).state()).isEqualTo(JobState.COMPLETED);
            assertThat(runs).hasValue(2);
        }
    }
}
