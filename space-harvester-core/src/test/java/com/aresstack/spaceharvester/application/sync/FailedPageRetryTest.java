package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.export.SpaceProvisioning;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * „Nur Fehler neu versuchen“ (ADR 0025): Gescheiterte Seiten werden einzeln nachgefragt und geladen, ohne Inventur,
 * auf der bestätigten Generation; die übrigen Seiten bleiben, wie sie sind.
 */
class FailedPageRetryTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", null)
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", "1", "7", null);
    private final InMemorySyncStore store = new InMemorySyncStore();
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add,
            new JobLimits(2, 10, Duration.ofHours(1)), FakeSource.CLOCK);
    private final Set<String> failing = new HashSet<>();
    private final List<String> fetched = new ArrayList<>();
    private final PageContentFetcher fetcher = (sourceId, pageId) -> {
        fetched.add(pageId);
        if (failing.contains(pageId)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "kein Netz", null);
        }
        PageMetadata page = source.pageMetadata(pageId);
        return PageSnapshot.of(sourceId, page, new NativePayload(("{\"id\":\"" + pageId + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    };
    private final SpaceSyncJobs syncs = new SpaceSyncJobs(jobs, source.queries(), store, fetcher, FakeSource.CLOCK);
    private final List<PauseReason> paused = new ArrayList<>();

    /** Ein vollständiger Abgleich, bei dem Seite 3 nicht geladen werden konnte. */
    private void syncWithPage3Failing() {
        failing.add("3");
        syncs.start("wiki", "DOC", "client:a", SyncMode.FULL);
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
        failing.clear();
        fetched.clear();
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().missingPageIds()).containsExactly("3");
    }

    @Test
    void onlyTheFailedPageIsLoadedWithoutAnInventory() {
        syncWithPage3Failing();
        long before = store.confirmed("wiki", "DOC").orElseThrow().generation();
        // Jede Listung würde scheitern: ein Nachladen darf keine Inventur machen.
        source.failListingAtCall(0);

        SpaceProvisioning.Retried retried = syncs.retry(context(), "wiki", "DOC", Set.of("3"), header -> true);

        assertThat(retried.done()).isTrue();
        assertThat(retried.failedPages()).isEmpty();
        assertThat(retried.unchecked()).isEmpty();
        assertThat(fetched).containsExactly("3");
        SyncBaseline after = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(after.generation()).isEqualTo(before + 1);
        assertThat(after.missingPageIds()).isEmpty();
        assertThat(after.pages()).containsKeys("1", "2", "3");
        assertThat(store.repository.find(new PageKey("wiki", "3"))).isPresent();
    }

    @Test
    void aPageThatFailsAgainStaysMissingAndIsReported() {
        syncWithPage3Failing();
        failing.add("3");

        SpaceProvisioning.Retried retried = syncs.retry(context(), "wiki", "DOC", Set.of("3"), header -> true);

        assertThat(retried.failedPages()).extracting(header -> header.key().pageId()).containsExactly("3");
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().missingPageIds()).containsExactly("3");
    }

    @Test
    void aKnownPageWhoseRetryFailsBecomesStale() {
        syncWithPage3Failing();
        source.change("2", "2", 1);
        failing.add("2");

        SpaceProvisioning.Retried retried = syncs.retry(context(), "wiki", "DOC", Set.of("2"), header -> true);

        assertThat(retried.failedPages()).extracting(header -> header.key().pageId()).containsExactly("2");
        SyncBaseline after = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(after.pages()).containsKey("2");
        assertThat(after.stalePageIds()).containsExactly("2");
    }

    @Test
    void aPageTheCallerMayNotSeeIsNeitherLoadedNorReported() {
        syncWithPage3Failing();

        SpaceProvisioning.Retried retried = syncs.retry(context(), "wiki", "DOC", Set.of("3"),
                header -> !header.key().pageId().equals("3"));

        assertThat(retried.done()).isTrue();
        assertThat(retried.failedPages()).isEmpty();
        assertThat(retried.unchecked()).isEmpty();
        assertThat(retried.dropped()).containsExactly("3");
        assertThat(fetched).isEmpty();
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().missingPageIds()).containsExactly("3");
    }

    @Test
    void aPageTheSourceNoLongerKnowsIsDroppedAndOneItCannotCheckStaysUnchecked() {
        syncWithPage3Failing();
        source.put("4", "DOC", null, "1", null).failSinglePage("4", SourceAccessException.Reason.UNAVAILABLE);
        source.remove("3");

        SpaceProvisioning.Retried retried = syncs.retry(context(), "wiki", "DOC", Set.of("3", "4"), header -> true);

        assertThat(retried.failedPages()).isEmpty();
        assertThat(retried.unchecked()).containsExactly("4");
        assertThat(retried.dropped()).containsExactly("3");
        assertThat(fetched).isEmpty();
    }

    @Test
    void whenTheSourceStaysUnreachableTheRetryPauses() {
        syncWithPage3Failing();
        syncs.connectionLostAfter(2);
        source.put("4", "DOC", null, "1", null).failSinglePage("3", SourceAccessException.Reason.UNAVAILABLE)
                .failSinglePage("4", SourceAccessException.Reason.UNAVAILABLE);

        syncs.retry(context(), "wiki", "DOC", Set.of("3", "4"), header -> true);

        assertThat(paused).containsExactly(PauseReason.CONNECTION_LOST);
    }

    @Test
    void withoutAConfirmedGenerationNothingIsRetried() {
        assertThat(syncs.retry(context(), "wiki", "DOC", Set.of("3"), header -> true))
                .isEqualTo(SpaceProvisioning.Retried.UNAVAILABLE);
        assertThat(fetched).isEmpty();
    }

    private JobContext context() {
        return new JobContext() {
            @Override
            public String jobId() {
                return "export-1";
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public boolean cancelRequested() {
                return false;
            }

            @Override
            public void discovered(long pages) {
            }

            @Override
            public void processed(long pages) {
            }

            @Override
            public void written(long pages) {
            }

            @Override
            public void failed(long pages) {
            }

            @Override
            public void pause(PauseReason reason) {
                paused.add(reason);
            }
        };
    }
}
