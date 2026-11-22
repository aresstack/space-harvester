package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.SourceDataPurge;
import com.aresstack.spaceharvester.application.sources.SourceRemovalPendingException;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Entfernen einer Quelle mit angehaltener Ablage (Fundstelle 22, Abnahme Punkt 10): die Sperre der Kennung und das
 * Wiederanlegen per YAML vor dem Neustart, deterministisch; erfundene Quellen.
 */
class SourceManagementWithdrawalTest {

    private final InMemoryJobStore store = new InMemoryJobStore();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> cleared = ConcurrentHashMap.newKeySet();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() {
        release.countDown();
        executor.shutdownNow();
    }

    private Jobs jobs() {
        return new Jobs(store, executor, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
    }

    /** Die Seitenablage hält an, bis der Test sie freigibt. */
    private List<SourceDataPurge.Store> heldStores() {
        return List.of(new SourceDataPurge.Store("Seiten", id -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), new SourceDataPurge.Store("Analysen", cleared::add));
    }

    private static SourceSettingsConfig handbuch() {
        return new SourceSettingsConfig("handbuch", "confluence", "Handbuch", URI.create("http://127.0.0.1:1/wiki"),
                SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000"));
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 30 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    @Test
    void theIdStaysLockedWhileTheDataOfTheRemovedSourceIsDeleted() throws Exception {
        Jobs jobs = jobs();
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceDataPurge purge = new SourceDataPurge(jobs, heldStores(), id -> catalog.config(id).isPresent());
            SourceManagement sources = new SourceManagement(catalog, jobs, purge);

            assertThat(sources.remove("handbuch")).isTrue();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            JobStatus removal = sources.removalPending("handbuch").orElseThrow();
            assertThat(sources.removalsPending()).containsOnlyKeys("handbuch");
            assertThatThrownBy(() -> sources.add(handbuch())).isInstanceOf(SourceRemovalPendingException.class);

            release.countDown();
            assertThat(jobs.await(removal.id(), Harvester.OWNER, Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            eventually(() -> sources.removalPending("handbuch").isEmpty());
            sources.add(handbuch());
            assertThat(sources.enabled("handbuch")).isTrue();
            assertThat(cleared).containsExactly("handbuch");
        } finally {
            jobs.close();
        }
    }

    @Test
    void aSourceConfiguredAgainBeforeTheRestartStaysInactiveUntilItsOldDataIsGone() throws Exception {
        Jobs before = jobs();
        String removal;
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceManagement sources = new SourceManagement(catalog, before,
                    new SourceDataPurge(before, heldStores(), id -> catalog.config(id).isPresent()));
            sources.remove("handbuch");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            removal = sources.removalPending("handbuch").orElseThrow().id();
        }
        // Die App endet mitten im Löschen; danach steht die Quelle per YAML wieder in der Konfiguration.
        before.close();

        CountDownLatch again = new CountDownLatch(1);
        Jobs after = jobs();
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceDataPurge purge = new SourceDataPurge(after, List.of(new SourceDataPurge.Store("Analysen", id -> {
                try {
                    again.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cleared.add(id);
            })), id -> catalog.config(id).isPresent());
            SourceManagement sources = new SourceManagement(catalog, after, purge);

            assertThat(sources.resumeWithdrawals()).extracting(JobStatus::id).containsExactly(removal);
            // Halb gelöschte Daten übernimmt sie nie: Sie ruht, bis das Löschen durch ist.
            assertThat(sources.enabled("handbuch")).isFalse();
            assertThat(sources.removalPending("handbuch")).isPresent();

            again.countDown();
            assertThat(after.await(removal, Harvester.OWNER, Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            eventually(() -> sources.enabled("handbuch"));
            assertThat(sources.removalPending("handbuch")).isEmpty();
            assertThat(cleared).contains("handbuch");
        } finally {
            after.close();
        }
    }
}
