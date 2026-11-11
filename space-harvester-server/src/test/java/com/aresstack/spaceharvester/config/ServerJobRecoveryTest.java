package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobEventStore;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.jobs.RouteLookup;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wiederanlauf im Dienst: ein Export nur mit der Arbeit vom Start, die die Prüfung des Auftraggebers trägt. */
class ServerJobRecoveryTest {

    private final InMemoryJobStore store = new InMemoryJobStore();
    private final List<String> performed = new ArrayList<>();

    @Test
    void aRetainedExportResumesAndAnExportWithoutItsWorkStaysPausedForTheClient() {
        Jobs jobs = jobs(store);
        JobStatus export = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a",
                JobTask.blocking(context -> context.pause(PauseReason.CONNECTION_LOST)));
        awaitPaused(jobs, export.id());
        ServerJobRecovery recovery = new ServerJobRecovery(jobs, (status, action) -> performed.add(status.id()));

        recovery.perform(jobs.status(export.id()), JobAction.RESUME);
        assertThat(performed).containsExactly(export.id());

        // Nach einem Neustart hält der Dienst die Arbeit nicht mehr.
        jobs.close();
        Jobs restarted = jobs(store);
        new ServerJobRecovery(restarted, (status, action) -> performed.add("again"))
                .perform(restarted.status(export.id()), JobAction.RESUME);
        assertThat(performed).containsExactly(export.id());
        assertThat(restarted.status(export.id()).state()).isEqualTo(JobState.PAUSED);
        restarted.close();
    }

    @Test
    void otherJobsAlwaysGoToTheActions() {
        Jobs jobs = jobs(store);
        JobStatus images = jobs.submit(JobType.ASSETS, "wiki", "DEV", "client-a", JobTask.blocking(context -> { }));
        new ServerJobRecovery(jobs, (status, action) -> performed.add(status.id())).perform(images,
                JobAction.RESUME);
        assertThat(performed).containsExactly(images.id());
        jobs.close();
    }

    private static Jobs jobs(InMemoryJobStore store) {
        return new Jobs(store, new InMemoryJobEventStore(), RouteLookup.NONE,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(1)), Clock.systemUTC());
    }

    private static void awaitPaused(Jobs jobs, String id) {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (jobs.status(id).state() != JobState.PAUSED && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertThat(jobs.status(id).state()).isEqualTo(JobState.PAUSED);
    }
}
