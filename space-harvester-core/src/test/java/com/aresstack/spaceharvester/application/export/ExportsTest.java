package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExportsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
    // Direkter Executor: der Auftrag läuft innerhalb von start(), die Tests bleiben deterministisch.
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final RecordingExportArtifactWriter writer = new RecordingExportArtifactWriter();
    private final InMemoryPageCache cache = new InMemoryPageCache();

    private Exports exports(PageCacheReader reader) {
        return new Exports(jobs, reader, writer, clock);
    }

    private static List<PageSnapshot> generated(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> page("confluence", "DEV", String.format("%05d", i),
                        i == 0 ? null : String.format("%05d", i / 10)))
                .toList();
    }

    @Test
    void exportsConfirmedGenerationAsCompleteFlatPagesAndIdTree() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null),
                page("confluence", "DEV", "101", "100"), page("confluence", "DEV", "200", null),
                page("confluence", "DEV", "102", "100")));

        JobStatus job = exports(cache).start("confluence", "DEV", "client-a");

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.COMPLETED);
        assertThat(status.owner()).isEqualTo("client-a");
        assertThat(status.progress()).isEqualTo(new JobProgress(4, 4, 4, 0));
        RecordingExportArtifactWriter.Published artifact = writer.published.get(job.id());
        assertThat(artifact.pageIds()).containsExactly("100", "101", "102", "200");
        assertThat(artifact.tree().roots()).containsExactly(
                new PageTree.Node("100", List.of(new PageTree.Node("101", List.of()),
                        new PageTree.Node("102", List.of()))),
                new PageTree.Node("200", List.of()));
        assertThat(artifact.summary().complete()).isTrue();
        assertThat(artifact.summary().pagesWritten()).isEqualTo(4);
        assertThat(artifact.summary().warnings()).isEmpty();
        assertThat(artifact.summary().scope().generation().id()).isEqualTo("g1");
        assertThat(exports(cache).artifact(job.id(), "client-a")).get().extracting(ExportArtifact::complete)
                .isEqualTo(true);
    }

    @Test
    void artifactOfAnotherClientIsUnknown() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "1", null)));
        JobStatus job = exports(cache).start("confluence", "DEV", "client-a");

        assertThatThrownBy(() -> exports(cache).artifact(job.id(), "client-b"))
                .isInstanceOf(UnknownJobException.class);
    }

    @Test
    void readsInBatchesUntilAShortBatch() {
        int count = Exports.BATCH_SIZE * 2 + 1;
        cache.publish("confluence", "DEV", confirmed("g1"), generated(count));
        List<String> afters = new ArrayList<>();
        PageCacheReader counting = new ForwardingPageCache(cache) {
            @Override
            public List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId,
                                          int limit) {
                afters.add(afterPageId);
                return super.pages(sourceId, spaceKey, generationId, afterPageId, limit);
            }
        };

        JobStatus job = exports(counting).start("confluence", "DEV", "client-a");

        assertThat(afters).hasSize(3).first().isNull();
        assertThat(writer.published.get(job.id()).summary().pagesWritten()).isEqualTo(count);
        assertThat(writer.published.get(job.id()).tree().nodeCount()).isEqualTo(count);
    }

    @Test
    void unconfirmedGenerationIsNeverComplete() {
        for (CacheState state : List.of(CacheState.INCOMPLETE, CacheState.STALE)) {
            cache.publish("confluence", "DEV", new CacheGeneration("g-" + state, state, null, null),
                    List.of(page("confluence", "DEV", "1", null)));

            RecordingExportArtifactWriter.Published artifact = writer.published.get(
                    exports(cache).start("confluence", "DEV", "client-a").id());

            assertThat(artifact.summary().complete()).isFalse();
            assertThat(artifact.summary().warnings()).anyMatch(w -> w.contains(state.name()));
        }
    }

    @Test
    void pageWithoutPayloadMakesTheArtifactIncompleteAndIsListed() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "1", null),
                page("confluence", "DEV", "3", "2")));
        // Seite 2 kennt der Cache nur aus den Kopfdaten, ihre Payload fehlt noch.
        cache.updateHeader("DEV", PageHeader.of("confluence", page("confluence", "DEV", "2", "1").metadata(),
                Instant.parse("2026-10-16T20:00:00Z")));

        JobStatus job = exports(cache).start("confluence", "DEV", "client-a");

        assertThat(jobs.status(job.id()).progress()).isEqualTo(new JobProgress(3, 3, 2, 1));
        ExportSummary summary = writer.published.get(job.id()).summary();
        assertThat(summary.complete()).isFalse();
        assertThat(summary.failures()).extracting(PageFailure::pageId, PageFailure::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2", "PAYLOAD_MISSING"));
        // Seite 3 hängt an der fehlenden Seite 2 und wird Wurzel; jede Seite im Baum steht im Seiten-Array.
        assertThat(writer.published.get(job.id()).tree().roots()).extracting(PageTree.Node::pageId)
                .containsExactly("1", "3");
        assertThat(summary.warnings()).anyMatch(w -> w.contains("Seite 3") && w.contains("Elternseite"));
    }

    @Test
    void pageChangedDuringExportIsNotMixedIn() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "1", null),
                page("confluence", "DEV", "2", "1")));
        PageCacheReader changing = new ForwardingPageCache(cache) {
            @Override
            public Optional<CachedPage> page(PageKey key) {
                if (key.pageId().equals("2")) {
                    PageSnapshot newer = InMemoryPageCache.page("confluence", "DEV", "2", "1",
                            "{\"id\":\"2\",\"v\":4}".getBytes());
                    return Optional.of(new CachedPage(newer.header(), newer, confirmed("g2")));
                }
                return super.page(key);
            }
        };

        JobStatus job = exports(changing).start("confluence", "DEV", "client-a");

        ExportSummary summary = writer.published.get(job.id()).summary();
        assertThat(summary.complete()).isFalse();
        assertThat(summary.failures()).extracting(PageFailure::code).containsExactly("CHANGED_DURING_EXPORT");
        assertThat(writer.published.get(job.id()).pageIds()).containsExactly("1");
    }

    @Test
    void notCachedSpaceIsReadInByTheExportItself() {
        List<String> provisioned = new ArrayList<>();
        SpaceProvisioning provisioning = new SpaceProvisioning() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Provisioned provision(com.aresstack.spaceharvester.application.jobs.JobContext context,
                                         String sourceId, String spaceKey) {
                provisioned.add(sourceId + "/" + spaceKey);
                // wie das echte Einlesen: Inventur und Beschaffung zählt das Einlesen, Schreiben der Export
                context.discovered(2);
                context.processed(2);
                cache.publish(sourceId, spaceKey, confirmed("g1"), List.of(page(sourceId, spaceKey, "1", null),
                        page(sourceId, spaceKey, "2", "1")));
                return new Provisioned(true, false, java.util.List.of());
            }

            @Override
            public Provisioned provision(com.aresstack.spaceharvester.application.jobs.JobContext context,
                                         String sourceId, String spaceKey,
                                         java.util.function.Predicate<
                                                 com.aresstack.spaceharvester.application.snapshot.PageHeader> counted) {
                // Ohne nicht lesbare Seiten zählen beide.
                return provision(context, sourceId, spaceKey);
            }
        };

        JobStatus job = new Exports(jobs, cache, writer,
                com.aresstack.spaceharvester.application.convert.ExportConverters.none(), provisioning, clock)
                .start("confluence", "OTHER", "client-a");

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.COMPLETED);
        assertThat(status.progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        assertThat(provisioned).containsExactly("confluence/OTHER");
        assertThat(writer.published.get(job.id()).pageIds()).containsExactly("1", "2");
        assertThat(writer.published.get(job.id()).summary().complete()).isTrue();
    }

    @Test
    void replacedGenerationDiscardsThePartialArtifact() {
        cache.publish("confluence", "DEV", confirmed("g1"), generated(Exports.BATCH_SIZE * 3));
        PageCacheReader replacing = new ForwardingPageCache(cache) {
            @Override
            public List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId,
                                          int limit) {
                if (afterPageId != null) {
                    cache.publish("confluence", "DEV", confirmed("g2"), generated(1));
                }
                return super.pages(sourceId, spaceKey, generationId, afterPageId, limit);
            }
        };

        JobStatus job = exports(replacing).start("confluence", "DEV", "client-a");

        assertThat(jobs.status(job.id()).error().code()).isEqualTo("GENERATION_CHANGED");
        assertThat(writer.published).isEmpty();
        assertThat(writer.discarded).containsExactly(job.id());
    }

    @Test
    void unavailableCacheFailsTheJob() {
        JobStatus job = exports(PageCacheReader.unavailable()).start("confluence", "DEV", "client-a");

        assertThat(jobs.status(job.id()).error().code()).isEqualTo("CACHE_UNAVAILABLE");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void unsortedListingFailsInsteadOfPublishingAnInconsistentArtifact() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "1", null),
                page("confluence", "DEV", "2", null)));
        PageCacheReader unsorted = new ForwardingPageCache(cache) {
            @Override
            public List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId,
                                          int limit) {
                List<ListedPage> pages = new ArrayList<>(super.pages(sourceId, spaceKey, generationId, afterPageId,
                        limit));
                return pages.reversed();
            }
        };

        JobStatus job = exports(unsorted).start("confluence", "DEV", "client-a");

        assertThat(jobs.status(job.id()).error().code()).isEqualTo("INCONSISTENT_READ");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void cancelledExportPublishesNothing() {
        List<Runnable> deferred = new ArrayList<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), deferred::add, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        cache.publish("confluence", "DEV", confirmed("g1"), generated(Exports.BATCH_SIZE * 3));
        AtomicReference<String> jobId = new AtomicReference<>();
        PageCacheReader cancelling = new ForwardingPageCache(cache) {
            @Override
            public List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId,
                                          int limit) {
                if (afterPageId != null) {
                    // Abbruch während des zweiten Stapels, wie von einer zweiten Anfrage.
                    jobs.cancel(jobId.get());
                }
                return super.pages(sourceId, spaceKey, generationId, afterPageId, limit);
            }
        };

        jobId.set(new Exports(jobs, cancelling, writer, clock).start("confluence", "DEV", "client-a").id());
        deferred.removeFirst().run();

        JobStatus status = jobs.status(jobId.get());
        assertThat(status.state()).isEqualTo(JobState.CANCELLED);
        assertThat(status.progress().written()).isEqualTo(Exports.BATCH_SIZE);
        assertThat(writer.published).isEmpty();
        assertThat(writer.discarded).containsExactly(jobId.get());
    }
    @Test
    void artifactIsRemovedTogetherWithItsExpiredJob() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T12:00:00Z"));
        Clock moving = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        Jobs expiring = new Jobs(new InMemoryJobStore(), Runnable::run,
                new JobLimits(1, 5, Duration.ofHours(1), Duration.ofDays(7)), moving);
        Exports exports = new Exports(expiring, cache, writer, moving);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "1", null)));
        JobStatus old = exports.start("confluence", "DEV", "client-a");

        now.set(now.get().plus(Duration.ofDays(6)));
        assertThat(exports.purge()).isZero();
        assertThat(writer.published()).containsExactly(old.id());

        now.set(now.get().plus(Duration.ofDays(2)));
        JobStatus fresh = exports.start("confluence", "DEV", "client-a");

        assertThat(writer.published()).containsExactly(fresh.id());
        assertThatThrownBy(() -> expiring.status(old.id())).isInstanceOf(UnknownJobException.class);
    }

    @Test
    void leftoversWithoutRunningJobAreRemovedOnStartup() {
        writer.unpublished.add("crashed-run");
        writer.published.put("lost-job", new RecordingExportArtifactWriter.Published(List.of(), null,
                null, java.util.Map.of()));

        exports(cache);

        assertThat(writer.unpublished()).isEmpty();
        assertThat(writer.discarded).containsExactly("crashed-run");
        assertThat(writer.published()).isEmpty();
    }

    @Test
    void artifactOfRunningOrQueuedExportIsKept() {
        List<Runnable> pending = new ArrayList<>();
        Jobs queued = new Jobs(new InMemoryJobStore(), pending::add, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        Exports exports = new Exports(queued, cache, writer, clock);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "1", null)));
        JobStatus job = exports.start("confluence", "DEV", "client-a");
        // Ein Lauf hat schon begonnen zu schreiben, der Auftrag ist noch nicht beendet.
        writer.unpublished.add(job.id());

        assertThat(exports.purge()).isZero();
        assertThat(writer.unpublished()).containsExactly(job.id());
    }

    @Test
    void artifactOfAnotherJobTypeIsNotAnExport() {
        JobStatus sync = jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "confluence", "DEV",
                null, context -> java.util.concurrent.CompletableFuture.completedFuture(null));
        writer.published.put(sync.id(), new RecordingExportArtifactWriter.Published(List.of(), null, null,
                java.util.Map.of()));

        exports(cache).purge();

        assertThat(writer.published()).isEmpty();
    }
}
