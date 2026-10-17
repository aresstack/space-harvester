package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobsConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JobsConfiguration.class);

    @Test
    void defaults() {
        context.run(app -> assertThat(app.getBean(JobsProperties.class))
                .isEqualTo(new JobsProperties(2, 20, Duration.ofHours(6), Duration.ofDays(7), 4)));
    }

    @Test
    void bindsLimits() {
        context.withPropertyValues("space-harvester.jobs.max-concurrent=1", "space-harvester.jobs.max-queued=0",
                        "space-harvester.jobs.max-run-time=30m", "space-harvester.jobs.retention=1d")
                .run(app -> assertThat(app.getBean(JobsProperties.class))
                        .isEqualTo(new JobsProperties(1, 0, Duration.ofMinutes(30), Duration.ofDays(1), 4)));
    }

    @Test
    void closingTheContextStopsTheJobs() {
        Jobs[] jobs = new Jobs[1];
        JobStatus[] waiting = new JobStatus[1];
        context.withPropertyValues("space-harvester.jobs.max-concurrent=1").run(app -> {
            jobs[0] = app.getBean(Jobs.class);
            jobs[0].submit(JobType.EXPORT, "confluence", "A", "client-a", context -> new CompletableFuture<>());
            waiting[0] = jobs[0].submit(JobType.EXPORT, "confluence", "B", "client-a", JobTask.blocking(context -> { }));
        });

        assertThat(jobs[0].status(waiting[0].id()).state()).isEqualTo(JobState.CANCELLED);
        assertThatThrownBy(() -> jobs[0].submit(JobType.EXPORT, "confluence", "C", "client-a", JobTask.blocking(context -> { })))
                .isInstanceOf(IllegalStateException.class);
    }
}
