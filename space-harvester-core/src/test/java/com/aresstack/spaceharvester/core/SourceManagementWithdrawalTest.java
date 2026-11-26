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
    void turningAMovedSourceOffKeepsDeletingItsOldData() throws Exception {
        Jobs jobs = jobs();
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceManagement sources = new SourceManagement(catalog, jobs,
                    new SourceDataPurge(jobs, heldStores(), id -> catalog.config(id).isPresent()));
            SourceSettingsConfig moved = new SourceSettingsConfig("handbuch", "confluence", "Handbuch",
                    URI.create("http://127.0.0.1:2/wiki"), SourceSettingsConfig.Auth.anonymous(),
                    Map.of("connectTimeoutMillis", "2000"));
            assertThat(sources.change(moved)).isTrue();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            String withdrawal = sources.withdrawalOpen("handbuch");

            // Abschalten beendet die Aufträge der Quelle, nicht aber das Löschen ihrer alten Daten (Abnahme #1090).
            assertThat(sources.setEnabled("handbuch", false)).isTrue();
            assertThat(jobs.status(withdrawal).state()).isEqualTo(JobState.RUNNING);

            release.countDown();
            assertThat(jobs.await(withdrawal, Harvester.OWNER, Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(cleared).containsExactly("handbuch");
            assertThat(sources.enabled("handbuch")).isFalse();
        } finally {
            jobs.close();
        }
    }

    /** Entfernt die Quelle mitten im Löschen und beendet die App; danach steht sie per YAML wieder da. */
    private String removedBeforeTheRestart() throws InterruptedException {
        Jobs before = jobs();
        String removal;
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceManagement sources = new SourceManagement(catalog, before,
                    new SourceDataPurge(before, heldStores(), id -> catalog.config(id).isPresent()));
            sources.remove("handbuch");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            removal = sources.removalPending("handbuch").orElseThrow().id();
        }
        closeWhileDeleting(before, removal);
        return removal;
    }

    /**
     * Schließt wie das Neuladen der App, während die Seitenablage noch löscht: Das Schließen kehrt erst zurück, wenn der
     * Auftrag aufgehört hat; erst dann dürfen die Ablagen schließen (Angelo 26.11.2026).
     */
    private void closeWhileDeleting(Jobs jobs, String removal) throws InterruptedException {
        Thread closing = Thread.ofVirtual().start(jobs::close);
        eventually(() -> jobs.status(removal).cancelRequested());
        assertThat(closing.join(Duration.ofMillis(200))).as("Schließen wartet auf das laufende Löschen").isFalse();
        release.countDown();
        assertThat(closing.join(Duration.ofSeconds(30))).as("Schließen nach dem Ende des Auftrags").isTrue();
        assertThat(jobs.status(removal).state()).as("unterbrochen, setzt nach dem Neustart fort")
                .isEqualTo(JobState.FAILED);
    }

    /**
     * Angelo 26.11.2026: Quelle entfernt, gleich danach neu geladen, während ihre Daten noch gelöscht werden. Das
     * Schließen darf die Ablagen nicht unter dem laufenden Löschen wegnehmen: Es wartet, und nach dem Schließen
     * berührt der Auftrag keine Ablage mehr.
     */
    @Test
    void closingRightAfterRemovingWaitsUntilTheRunningDeletionHasLetGoOfTheStores() throws Exception {
        Jobs jobs = jobs();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean usedAfterClose = new java.util.concurrent.atomic.AtomicBoolean();
        List<SourceDataPurge.Store> stores = List.of(heldStores().get(0), new SourceDataPurge.Store("Analysen", id -> {
            usedAfterClose.compareAndSet(false, closed.get());
            cleared.add(id);
        }));
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceManagement sources = new SourceManagement(catalog, jobs,
                    new SourceDataPurge(jobs, stores, id -> catalog.config(id).isPresent()));
            assertThat(sources.remove("handbuch")).isTrue();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            String removal = sources.removalPending("handbuch").orElseThrow().id();

            closeWhileDeleting(jobs, removal);
            closed.set(true);

            assertThat(usedAfterClose).as("keine Ablage nach dem Schließen").isFalse();
            assertThat(cleared).as("die zweite Ablage kommt erst nach dem Neustart dran").isEmpty();
        }
    }

    /** Nach dem Neustart hält die Analysenablage an, bis {@code again} freigibt; danach kommt die Seitenablage. */
    private List<SourceDataPurge.Store> storesAfter(CountDownLatch again) {
        return List.of(new SourceDataPurge.Store("Analysen", id -> {
            try {
                again.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleared.add(id);
        }), new SourceDataPurge.Store("Seiten", cleared::add));
    }

    @Test
    void aSourceConfiguredAgainBeforeTheRestartStaysInactiveUntilItsOldDataIsGone() throws Exception {
        String removal = removedBeforeTheRestart();
        CountDownLatch again = new CountDownLatch(1);
        Jobs after = jobs();
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceDataPurge purge = new SourceDataPurge(after, storesAfter(again), id -> catalog.config(id).isPresent());
            SourceManagement sources = new SourceManagement(catalog, after, purge);

            assertThat(sources.resumeWithdrawals()).extracting(JobStatus::id).containsExactly(removal);
            // Halb gelöschte Daten übernimmt sie nie: Sie ruht, bis das Löschen durch ist.
            assertThat(sources.enabled("handbuch")).isFalse();
            assertThat(sources.removalPending("handbuch")).isPresent();
            assertThat(sources.withdrawalOpen("handbuch")).isEqualTo(removal);

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

    @Test
    void theUsersSwitchStaysOffWhenTheyTurnTheHeldSourceOff() throws Exception {
        String removal = removedBeforeTheRestart();
        CountDownLatch again = new CountDownLatch(1);
        Jobs after = jobs();
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceManagement sources = new SourceManagement(catalog, after,
                    new SourceDataPurge(after, storesAfter(again), id -> catalog.config(id).isPresent()));
            sources.resumeWithdrawals();
            // Der Benutzer schaltet die gehaltene Quelle aus: Das gilt nach dem Löschen.
            assertThat(sources.setEnabled("handbuch", false)).isFalse();

            again.countDown();
            assertThat(after.await(removal, Harvester.OWNER, Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            eventually(() -> sources.removalPending("handbuch").isEmpty());
            TimeUnit.MILLISECONDS.sleep(100);
            assertThat(sources.enabled("handbuch")).isFalse();
            assertThat(sources.setEnabled("handbuch", true)).isTrue();
        } finally {
            after.close();
        }
    }

    @Test
    void cancellingTheWithdrawalReleasesTheHeldSource() throws Exception {
        String removal = removedBeforeTheRestart();
        CountDownLatch again = new CountDownLatch(1);
        Jobs after = jobs();
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(handbuch()), "test")) {
            SourceManagement sources = new SourceManagement(catalog, after,
                    new SourceDataPurge(after, storesAfter(again), id -> catalog.config(id).isPresent()));
            sources.resumeWithdrawals();
            assertThat(sources.enabled("handbuch")).isFalse();

            after.cancel(removal, Harvester.OWNER);
            again.countDown();
            assertThat(after.await(removal, Harvester.OWNER, Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.CANCELLED);
            eventually(() -> sources.enabled("handbuch"));
            assertThat(sources.withdrawalOpen("handbuch")).isNull();
        } finally {
            after.close();
        }
    }
}
