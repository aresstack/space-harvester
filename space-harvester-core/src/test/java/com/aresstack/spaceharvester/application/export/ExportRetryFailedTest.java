package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.jobcontrol.JobActions;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * „Nur Fehler neu versuchen“ bei einem Download (ADR 0025, Angelo: „Die sechs fehlgeschlagenen Seiten gezielt
 * nachladen, ohne erneute Inventur“; „der retry macht keinen neuen auftrag mehr daraus“).
 */
class ExportRetryFailedTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T16:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final RecordingExportArtifactWriter writer = new RecordingExportArtifactWriter();
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final PageSnapshot one = page("confluence", "DEV", "1", null);
    private final PageSnapshot two = page("confluence", "DEV", "2", "1");
    private final PageSnapshot three = page("confluence", "DEV", "3", "1");
    private int inventories;
    private final List<Boolean> reloads = new ArrayList<>();
    private final List<Set<String>> retried = new ArrayList<>();
    /** Was das Nachladen meldet und veröffentlicht. */
    private SpaceProvisioning.Retried retryResult;
    private List<PageSnapshot> afterRetry;
    private Predicate<PageHeader> allowed;
    private boolean pauseOnce;

    /** Erstes Einlesen: Seite 3 stand in der Inventur, ihr Abruf scheiterte. */
    private final SpaceProvisioning provisioning = new SpaceProvisioning() {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Provisioned provision(JobContext context, String sourceId, String spaceKey) {
            inventories++;
            context.discovered(3);
            context.processed(3);
            cache.publish("confluence", "DEV", confirmed("g1"), List.of(one, two));
            return new Provisioned(true, false, List.of(three.header()));
        }

        @Override
        public Provisioned provisionScoped(JobContext context, String sourceId, String spaceKey,
                                           java.util.function.Function<List<PageHeader>, Predicate<PageHeader>> counting,
                                           java.util.function.Function<List<PageHeader>, Predicate<PageHeader>> loading,
                                           boolean reloadAll) {
            reloads.add(reloadAll);
            return provisionScoped(context, sourceId, spaceKey, counting, loading);
        }

        @Override
        public Retried retry(JobContext context, String sourceId, String spaceKey, Set<String> pageIds,
                             Predicate<PageHeader> allowed) {
            retried.add(Set.copyOf(pageIds));
            ExportRetryFailedTest.this.allowed = allowed;
            if (pauseOnce) {
                pauseOnce = false;
                context.pause(com.aresstack.spaceharvester.application.jobs.PauseReason.CONNECTION_LOST);
            }
            cache.publish("confluence", "DEV", confirmed("g2"), afterRetry);
            return retryResult;
        }
    };
    private final Exports exports = new Exports(jobs, cache, writer, ExportConverters.none(), provisioning, clock);
    private final JobActions actions = new JobActions(jobs, exports, null, null);

    private JobStatus exportWithPage3Failing(ExportRequest request) {
        JobStatus export = exports.start(request);
        JobStatus done = jobs.status(export.id());
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.lifecycle().key()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(done.lifecycle().actions()).containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH,
                JobAction.DELETE);
        assertThat(writer.published.get(export.id()).summary().unfetched()).containsExactly("3");
        return done;
    }

    private static ExportRequest dev() {
        return ExportRequest.of("confluence", "DEV", "client-a", List.of());
    }

    @Test
    void onlyTheFailedPageIsLoadedAgainInTheSameEntryWithoutAnInventory() {
        JobStatus export = exportWithPage3Failing(dev());
        afterRetry = List.of(one, two, three);
        retryResult = new SpaceProvisioning.Retried(true, List.of(), Set.of());

        JobStatus again = actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED);

        assertThat(again.id()).isEqualTo(export.id());
        assertThat(inventories).isEqualTo(1);
        assertThat(retried).containsExactly(Set.of("3"));
        JobStatus done = jobs.status(export.id());
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.lifecycle().key()).isEqualTo("COMPLETED");
        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 3, 0));
        ExportSummary summary = writer.published.get(export.id()).summary();
        assertThat(summary.complete()).isTrue();
        assertThat(summary.unfetched()).isEmpty();
        assertThat(writer.published.get(export.id()).pageIds()).containsExactly("1", "2", "3");
        assertThat(jobs.list("client-a", JobType.EXPORT)).hasSize(1);
    }

    @Test
    void aPageThatFailsAgainIsCountedOnceAndCanBeRetriedAgain() {
        JobStatus export = exportWithPage3Failing(dev());
        afterRetry = List.of(one, two);
        retryResult = new SpaceProvisioning.Retried(true, List.of(three.header()), Set.of());

        actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED);

        JobStatus done = jobs.status(export.id());
        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 2, 1));
        assertThat(done.lifecycle().actions()).containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH,
                JobAction.DELETE);
        assertThat(writer.published.get(export.id()).summary().unfetched()).containsExactly("3");
    }

    @Test
    void aPageTheSourceCouldNotCheckStaysFailed() {
        JobStatus export = exportWithPage3Failing(dev());
        afterRetry = List.of(one, two);
        retryResult = new SpaceProvisioning.Retried(true, List.of(), Set.of("3"));

        actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED);

        assertThat(jobs.status(export.id()).progress()).isEqualTo(new JobProgress(3, 3, 2, 1));
        ExportSummary summary = writer.published.get(export.id()).summary();
        assertThat(summary.complete()).isFalse();
        assertThat(summary.unfetched()).containsExactly("3");
    }

    @Test
    void readabilityIsCheckedAgainForEveryRetriedPage() {
        JobStatus export = exportWithPage3Failing(dev());
        afterRetry = List.of(one, two, three);
        retryResult = new SpaceProvisioning.Retried(true, List.of(), Set.of());

        actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED,
                request -> request.withVisible(header -> !header.key().pageId().equals("3")));

        assertThat(allowed.test(three.header())).isFalse();
        assertThat(allowed.test(two.header())).isTrue();
        // Die Seite ist jetzt nicht mehr lesbar: Sie fehlt, ohne erwähnt oder gezählt zu werden.
        assertThat(jobs.status(export.id()).progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        assertThat(writer.published.get(export.id()).pageIds()).containsExactly("1", "2");
    }

    @Test
    void aCleanDownloadOffersNoRetryOfFailures() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(one, two));
        JobStatus export = exports.start(dev());

        assertThat(jobs.status(export.id()).lifecycle().actions()).containsExactly(JobAction.FORCE_REFRESH,
                JobAction.DELETE);
        assertThatThrownBy(() -> actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED))
                .isInstanceOf(JobActionNotAllowedException.class);
    }

    @Test
    void whileImagesOrAttachmentsAreStillCollectedTheArtifactStays() {
        JobStatus export = exportWithPage3Failing(dev());
        jobs.submit(JobType.ASSETS, "confluence", "DEV", null, "client-a",
                Map.of(ExportAssetCollection.EXPORT_ID, export.id()), context -> new CompletableFuture<>());

        assertThatThrownBy(() -> actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED))
                .isInstanceOf(JobNotResumableException.class)
                .hasMessageContaining("Bilder oder Anhänge");
        assertThat(retried).isEmpty();
    }

    @Test
    void forcingReadsEverythingAnewInTheSameEntryAndReplacesTheResultAfterwards() {
        JobStatus export = exportWithPage3Failing(dev());
        assertThat(reloads).containsExactly(false);

        JobStatus again = actions.perform(export.id(), "client-a", JobAction.FORCE_REFRESH);

        assertThat(again.id()).isEqualTo(export.id());
        assertThat(reloads).containsExactly(false, true);
        assertThat(inventories).isEqualTo(2);
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(writer.published).containsKey(export.id());
        assertThat(jobs.list("client-a", JobType.EXPORT)).hasSize(1);
    }

    @Test
    void forcingIsRefusedWhileImagesAreCollected() {
        JobStatus export = exportWithPage3Failing(dev());
        jobs.submit(JobType.ATTACHMENTS, "confluence", "DEV", null, "client-a",
                Map.of(ExportAssetCollection.EXPORT_ID, export.id()), context -> new CompletableFuture<>());

        assertThatThrownBy(() -> actions.perform(export.id(), "client-a", JobAction.FORCE_REFRESH))
                .isInstanceOf(JobNotResumableException.class);
        assertThat(reloads).containsExactly(false);
    }

    @Test
    void aRetryThatPausedResumesAsARetryWithoutAnInventory() {
        JobStatus export = exportWithPage3Failing(dev());
        afterRetry = List.of(one, two, three);
        retryResult = new SpaceProvisioning.Retried(true, List.of(), Set.of());
        pauseOnce = true;

        actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED);
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.PAUSED);
        actions.perform(export.id(), "client-a", JobAction.RESUME);

        assertThat(retried).containsExactly(Set.of("3"), Set.of("3"));
        assertThat(inventories).isEqualTo(1);
        assertThat(jobs.status(export.id()).lifecycle().key()).isEqualTo("COMPLETED");
    }

    @Test
    void aPageTheSourceNoLongerKnowsLeavesTheResultWithoutBeingMentioned() {
        JobStatus export = exportWithPage3Failing(dev());
        // Ein alter Stand von Seite 3 liegt noch im Cache.
        afterRetry = List.of(one, two, three);
        retryResult = new SpaceProvisioning.Retried(true, List.of(), Set.of(), Set.of("3"));

        actions.perform(export.id(), "client-a", JobAction.RETRY_FAILED);

        assertThat(writer.published.get(export.id()).pageIds()).containsExactly("1", "2");
        assertThat(jobs.status(export.id()).progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        assertThat(writer.published.get(export.id()).summary().warnings()).isEmpty();
    }
}
