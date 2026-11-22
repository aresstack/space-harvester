package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Quellenübergreifende Aufträge lassen die Quellen aus, deren Löschen bei ihrem Start offen war (Abnahme #1090),
 * festgehalten am Auftrag und mitgegeben an die Threads, die seine Arbeit startet; erfundene Quellen.
 */
class JobsWithheldSourcesTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), executor, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.systemUTC());
    private final Set<String> open = ConcurrentHashMap.newKeySet();

    @AfterEach
    void close() {
        jobs.close();
        executor.shutdownNow();
    }

    @Test
    void aJobAcrossAllSourcesKeepsTheWithdrawalsOpenAtItsStartAlsoOnItsWorkerThreads() throws Exception {
        jobs.openWithdrawals(() -> Set.copyOf(open));
        open.add("altwiki");
        AtomicReference<Set<String>> onJob = new AtomicReference<>();
        AtomicReference<Set<String>> onWorker = new AtomicReference<>();
        AtomicReference<Set<String>> afterChange = new AtomicReference<>();

        JobStatus all = jobs.submit(JobType.SEARCH_QUALITY, Jobs.ALL_SOURCES, null, "app",
                JobTask.blocking(context -> {
                    onJob.set(Jobs.withheldSources());
                    // Ändert sich die Menge danach, bleibt es bei der vom Start.
                    open.add("handbuch");
                    afterChange.set(Jobs.withheldSources());
                    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
                        workers.submit(Jobs.carryingWithheld(() -> {
                            onWorker.set(Jobs.withheldSources());
                            return null;
                        })).get();
                    }
                }));
        assertThat(jobs.await(all.id(), "app", Duration.ofSeconds(30)).state()).isEqualTo(JobState.COMPLETED);

        assertThat(onJob.get()).containsExactly("altwiki");
        assertThat(afterChange.get()).containsExactly("altwiki");
        assertThat(onWorker.get()).containsExactly("altwiki");
        assertThat(Jobs.withheldSources()).as("außerhalb eines Auftrags").isEmpty();
    }

    @Test
    void aJobOfOneSourceWithholdsNothing() throws Exception {
        jobs.openWithdrawals(() -> Set.of("altwiki"));
        AtomicReference<Set<String>> seen = new AtomicReference<>();

        JobStatus one = jobs.submit(JobType.SYNC, "handbuch", null, "app",
                JobTask.blocking(context -> seen.set(Jobs.withheldSources())));

        assertThat(jobs.await(one.id(), "app", Duration.ofSeconds(30)).state()).isEqualTo(JobState.COMPLETED);
        assertThat(seen.get()).isEmpty();
    }
}
