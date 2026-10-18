package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import org.junit.jupiter.api.Test;

/**
 * Export ohne vorbereiteten Bestand (ADR 0010): Der Export liest einen fehlenden, unbestätigten oder ausdrücklich
 * aktuell verlangten Space im eigenen Auftrag ein und exportiert danach die bestätigte Generation.
 */
class ExportProvisioningTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T20:00:00Z"), ZoneOffset.UTC);
    private final List<Runnable> deferred = new ArrayList<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final RecordingExportArtifactWriter writer = new RecordingExportArtifactWriter();
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final List<String> provisioned = new ArrayList<>();

    /** Einlesen wie ein Abgleich: veröffentlicht die Seiten der Quelle als Generation und meldet das Ergebnis. */
    private SpaceProvisioning provisioning(Function<JobContext, SpaceProvisioning.Provisioned> run) {
        return new SpaceProvisioning() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Provisioned provision(JobContext context, String sourceId, String spaceKey) {
                return provision(context, sourceId, spaceKey, header -> false);
            }

            @Override
            public Provisioned provision(JobContext context, String sourceId, String spaceKey,
                                         Predicate<PageHeader> counted) {
                provisioned.add(sourceId + "/" + spaceKey);
                ExportProvisioningTest.this.counted = counted;
                return run.apply(context);
            }
        };
    }

    private Predicate<PageHeader> counted = header -> false;

    /** Zählt wie das echte Einlesen: Inventur und Beschaffung nur für Seiten, die der Auftraggeber lesen darf. */
    private void count(JobContext context, List<PageSnapshot> inventory) {
        long visible = inventory.stream().map(PageSnapshot::header).filter(counted).count();
        context.discovered(visible);
        context.processed(visible);
    }

    private SpaceProvisioning publishing(String generation, List<PageSnapshot> pages, Set<String> failed) {
        return provisioning(context -> {
            count(context, pages);
            cache.publish("confluence", "DEV", confirmed(generation), pages);
            return new SpaceProvisioning.Provisioned(true, false, pages.stream()
                    .filter(page -> failed.contains(page.key().pageId())).map(PageSnapshot::header).toList());
        });
    }

    private Exports exports(SpaceProvisioning provisioning) {
        return new Exports(jobs, cache, writer, ExportConverters.none(), provisioning, clock);
    }

    private static List<PageSnapshot> twoPages() {
        return List.of(page("confluence", "DEV", "1", null), page("confluence", "DEV", "2", "1"));
    }

    private JobStatus run(Exports exports, ExportRequest request) {
        return jobs.status(exports.start(request).id());
    }

    private static ExportRequest dev() {
        return ExportRequest.of("confluence", "DEV", "client-a", List.of());
    }

    @Test
    void confirmedGenerationIsReusedWithoutReadingTheSource() {
        cache.publish("confluence", "DEV", confirmed("g1"), twoPages());

        JobStatus status = run(exports(publishing("g2", twoPages(), Set.of())), dev());

        assertThat(status.state()).isEqualTo(JobState.COMPLETED);
        assertThat(provisioned).isEmpty();
        assertThat(writer.published.get(status.id()).summary().scope().generation().id()).isEqualTo("g1");
    }

    @Test
    void refreshReadsTheSourceEvenWithAConfirmedGeneration() {
        cache.publish("confluence", "DEV", confirmed("g1"), twoPages());

        JobStatus status = run(exports(publishing("g2", twoPages(), Set.of())), dev().withRefresh(true));

        assertThat(provisioned).containsExactly("confluence/DEV");
        assertThat(writer.published.get(status.id()).summary().scope().generation().id()).isEqualTo("g2");
        assertThat(writer.published.get(status.id()).summary().complete()).isTrue();
    }

    @Test
    void unconfirmedGenerationIsReadInAgain() {
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.STALE, null, null), twoPages());

        JobStatus status = run(exports(publishing("g2", twoPages(), Set.of())), dev());

        assertThat(provisioned).containsExactly("confluence/DEV");
        assertThat(writer.published.get(status.id()).summary().complete()).isTrue();
    }

    @Test
    void pagesThatFailedToLoadNeverMakeACompleteExport() {
        JobStatus status = run(exports(publishing("g1", twoPages(), Set.of("2"))), dev());

        assertThat(status.state()).isEqualTo(JobState.COMPLETED);
        ExportSummary summary = writer.published.get(status.id()).summary();
        assertThat(summary.complete()).isFalse();
        assertThat(summary.warnings()).anyMatch(w -> w.startsWith("1 Seiten ließen sich beim Einlesen nicht"));
    }

    @Test
    void aNewPageThatNeverReachedTheCacheStillMakesTheExportIncomplete() {
        PageSnapshot missing = page("confluence", "DEV", "3", "1");
        JobStatus status = run(exports(provisioning(context -> {
            // Seite 3 stand in der Inventur, ihr erster Abruf scheiterte: kein Stand im Cache, keine Seitenliste.
            count(context, List.of(twoPages().get(0), twoPages().get(1), missing));
            cache.publish("confluence", "DEV", confirmed("g1"), twoPages());
            return new SpaceProvisioning.Provisioned(true, false, List.of(missing.header()));
        })), dev());

        ExportSummary summary = writer.published.get(status.id()).summary();
        assertThat(summary.complete()).isFalse();
        assertThat(summary.warnings()).anyMatch(w -> w.startsWith("1 Seiten ließen sich beim Einlesen nicht"));
        assertThat(jobs.status(status.id()).progress()).isEqualTo(new JobProgress(3, 3, 2, 1));
    }

    @Test
    void aMissingPageTheCallerMayNotReadIsNeverMentioned() {
        PageSnapshot missing = page("confluence", "DEV", "3", "1");
        JobStatus status = run(exports(provisioning(context -> {
            count(context, List.of(twoPages().get(0), twoPages().get(1), missing));
            cache.publish("confluence", "DEV", confirmed("g1"), twoPages());
            return new SpaceProvisioning.Provisioned(true, false, List.of(missing.header()));
        })), dev().withVisible(header -> !header.key().pageId().equals("3")));

        ExportSummary summary = writer.published.get(status.id()).summary();
        assertThat(summary.complete()).isTrue();
        assertThat(summary.warnings()).isEmpty();
        assertThat(jobs.status(status.id()).progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
    }

    @Test
    void unconfirmableReadInWithoutCacheFailsWithoutArtifact() {
        JobStatus status = run(exports(provisioning(context -> SpaceProvisioning.Provisioned.NOT_CONFIRMED)),
                dev());

        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("SYNC_INCOMPLETE");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void unconfirmableRefreshDoesNotFallBackToTheOldGeneration() {
        cache.publish("confluence", "DEV", confirmed("g1"), twoPages());

        JobStatus status = run(exports(provisioning(context -> SpaceProvisioning.Provisioned.NOT_CONFIRMED)),
                dev().withRefresh(true));

        assertThat(status.error().code()).isEqualTo("SYNC_INCOMPLETE");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void unconfirmableReadInFallsBackToAnUnconfirmedGenerationAsIncomplete() {
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, null, null),
                twoPages());

        JobStatus status = run(exports(provisioning(context -> SpaceProvisioning.Provisioned.NOT_CONFIRMED)),
                dev());

        ExportSummary summary = writer.published.get(status.id()).summary();
        assertThat(summary.complete()).isFalse();
        assertThat(summary.warnings()).anyMatch(w -> w.contains("bisherige Bestand"));
    }

    @Test
    void aConcurrentSyncThatConfirmedFirstIsUsed() {
        JobStatus status = run(exports(provisioning(context -> {
            cache.publish("confluence", "DEV", confirmed("other"), twoPages());
            return new SpaceProvisioning.Provisioned(true, true, List.of());
        })), dev());

        ExportSummary summary = writer.published.get(status.id()).summary();
        assertThat(summary.scope().generation().id()).isEqualTo("other");
        assertThat(summary.complete()).isTrue();
    }

    @Test
    void unreachableSourceFailsWithItsReason() {
        JobStatus status = run(exports(provisioning(context -> {
            throw new SourceAccessException("confluence", SourceAccessException.Reason.UNAVAILABLE,
                    "Zeitüberschreitung", null);
        })), dev());

        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void unknownSourceFails() {
        JobStatus status = run(exports(provisioning(context -> {
            throw new UnknownSourceException("confluence");
        })), dev());

        assertThat(status.error().code()).isEqualTo("UNKNOWN_SOURCE");
    }

    @Test
    void withoutReadInANotCachedSpaceFailsWithAClearReason() {
        JobStatus status = run(exports(SpaceProvisioning.unavailable()), dev());

        assertThat(status.error().code()).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void cancelDuringReadInPublishesNothing() {
        Jobs deferredJobs = new Jobs(new InMemoryJobStore(), deferred::add, new JobLimits(1, 5,
                Duration.ofHours(1)), clock);
        String[] id = new String[1];
        SpaceProvisioning cancelling = provisioning(context -> {
            deferredJobs.cancel(id[0]);
            context.checkpoint();
            return new SpaceProvisioning.Provisioned(true, false, List.of());
        });
        id[0] = new Exports(deferredJobs, cache, writer, ExportConverters.none(), cancelling, clock).start(dev())
                .id();
        deferred.removeFirst().run();

        assertThat(deferredJobs.status(id[0]).state()).isEqualTo(JobState.CANCELLED);
        assertThat(writer.published).isEmpty();
    }

    @Test
    void pagesTheCallerMayNotReadAreNotPartOfHisExportAndAreNeverMentioned() {
        List<PageSnapshot> pages = List.of(page("confluence", "DEV", "1", null), page("confluence", "DEV", "2", "1"),
                page("confluence", "DEV", "3", "2"));
        JobStatus status = run(exports(publishing("g1", pages, Set.of("2"))),
                dev().withVisible(header -> !header.key().pageId().equals("2")));

        RecordingExportArtifactWriter.Published artifact = writer.published.get(status.id());
        assertThat(artifact.pageIds()).containsExactly("1", "3");
        // Vollständig für seine Sicht; weder Zähler noch Warnungen verraten Seite 2 oder ihren Ladefehler.
        assertThat(artifact.summary().complete()).isTrue();
        assertThat(artifact.summary().warnings()).isEmpty();
        assertThat(artifact.summary().failures()).isEmpty();
        assertThat(jobs.status(status.id()).progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        // Auch die Diagnose nennt Seite 2 nicht, weder als Meldung noch als Detail.
        assertThat(jobs.events(status.id())).noneMatch(event -> event.message().contains("Seite 2")
                || event.message().contains("ließen sich beim Einlesen nicht") || event.detail() != null);
    }

    @Test
    void theReadInIsTraceableInTheDiagnostics() {
        JobStatus status = run(exports(publishing("g1", twoPages(), Set.of("2"))), dev());

        assertThat(jobs.events(status.id())).extracting(JobEvent::message).containsSubsequence(
                "Auftrag gestartet", "Phase: Bestand prüfen", "Phase: Einlesen",
                "Kein Bestand im Cache; der Space wird von der Quelle eingelesen", "Phase: Seiten exportieren",
                "Phase: Ablegen", "1 Seiten ließen sich beim Einlesen nicht abrufen; sie fehlen oder sind auf altem "
                        + "Stand");
        assertThat(jobs.events(status.id())).filteredOn(event -> event.severity() == JobEvent.Severity.WARNING)
                .extracting(JobEvent::category).containsOnly(JobEvent.Category.STORAGE);
    }

    @Test
    void aFailedReadInNamesItsPhase() {
        JobStatus status = run(exports(provisioning(context -> {
            throw new SourceAccessException("confluence", SourceAccessException.Reason.UNAVAILABLE,
                    "Zeitüberschreitung", null);
        })), dev());

        JobEvent failure = jobs.events(status.id()).getLast();
        assertThat(failure.code()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(failure.phase()).isEqualTo("Einlesen");
        assertThat(failure.category()).isEqualTo(JobEvent.Category.NETWORK);
    }

    @Test
    void revokedAccessBeforePublishingPublishesNothing() {
        int[] checks = {0};
        JobStatus status = run(exports(publishing("g1", twoPages(), Set.of())),
                dev().withAuthorized(() -> checks[0]++ == 0));

        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("ACCESS_REVOKED");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void aPageRevokedBeforePublishingPublishesNothing() {
        // Erste Prüfung zählt beim Einlesen, zweite wählt fürs Schreiben aus, die dritte vor dem Veröffentlichen.
        java.util.Map<String, Integer> checks = new java.util.HashMap<>();
        JobStatus status = run(exports(publishing("g1", twoPages(), Set.of())), dev().withVisible(header ->
                checks.merge(header.key().pageId(), 1, Integer::sum) <= 2 || !header.key().pageId().equals("2")));

        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("ACCESS_REVOKED");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void readInCountsNothingTheCallerCannotSee() {
        JobStatus status = run(exports(publishing("g1", twoPages(), Set.of())), dev());

        assertThat(jobs.status(status.id()).progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
    }
}
