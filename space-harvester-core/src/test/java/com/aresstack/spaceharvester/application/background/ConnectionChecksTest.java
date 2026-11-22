package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** „Verbindung prüfen“ als Hintergrundteil (Fundstelle 17); nur erfundene Quellen. */
class ConnectionChecksTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final InMemoryJobStore store = new InMemoryJobStore();
    private final Jobs jobs = new Jobs(store, executor, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
    private final BackgroundJobs background = new BackgroundJobs(jobs, "app");
    private final ConnectionChecks checks = new ConnectionChecks(background);

    @AfterEach
    void close() {
        jobs.close();
        executor.shutdownNow();
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 30 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private Optional<JobStatus> part() {
        List<JobStatus> parts = background.parts(ConnectionChecks.KIND);
        return parts.size() == 1 ? Optional.of(parts.getFirst()) : Optional.empty();
    }

    @Test
    void aSlowCheckRunsAsAVisiblePartUntilItSucceeds() throws Exception {
        ConnectionChecks.Check check = checks.begin("wiki").orElseThrow();
        eventually(() -> part().map(p -> p.state() == JobState.RUNNING).orElse(false));
        assertThat(part().orElseThrow().sourceId()).isEqualTo("wiki");
        assertThat(part().orElseThrow().parameter(BackgroundJobs.TITLE)).isEqualTo(ConnectionChecks.TITLE);

        check.finish(null);

        eventually(() -> part().orElseThrow().state() == JobState.COMPLETED);
        assertThat(part().orElseThrow().progress().written()).isEqualTo(1);
    }

    @Test
    void aFailedCheckEndsRedWithItsReason() throws Exception {
        ConnectionChecks.Check check = checks.begin("wiki").orElseThrow();
        eventually(() -> part().map(p -> p.state() == JobState.RUNNING).orElse(false));

        check.finish("erfundener Grund");

        eventually(() -> part().orElseThrow().state() == JobState.FAILED);
        JobStatus failed = part().orElseThrow();
        assertThat(failed.error().code()).isEqualTo(ConnectionChecks.FAILED);
        assertThat(failed.error().message()).contains("erfundener Grund");
        assertThat(failed.progress().failed()).isEqualTo(1);
    }

    @Test
    void aCheckThatEndsBeforeItsPartStartsLetsThePartEndAtOnce() throws Exception {
        checks.begin("wiki").orElseThrow().finish(null);

        eventually(() -> part().map(p -> p.state().terminal()).orElse(false));
        assertThat(part().orElseThrow().state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void aPartFromBeforeARestartEndsAtOnce() throws Exception {
        // Nach einem Neustart gibt es die Prüfung nicht mehr: Der Teil zeigt nichts und endet.
        BackgroundGroup group = background.start(ConnectionChecks.KIND, ConnectionChecks.TITLE,
                List.of(new BackgroundPart.Draft("wiki", null, Map.of())));

        eventually(() -> background.group(group.groupId()).orElseThrow().completed());
    }

    @Test
    void withOnlyOnePlaceNoPartIsCreated() {
        Jobs single = new Jobs(new InMemoryJobStore(), executor, new JobLimits(1, 10, Duration.ofHours(1)),
                Clock.systemUTC());
        try {
            ConnectionChecks one = new ConnectionChecks(new BackgroundJobs(single, "app"));
            assertThat(one.begin("wiki")).isEmpty();
        } finally {
            single.close();
        }
    }
}
