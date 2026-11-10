package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
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
 * „Neu erzwingen“ (ADR 0025): Das Einlesen lädt auch unveränderte Seiten neu, in eine neue Generation; eine Seite,
 * deren neuer Abruf scheitert, behält ihren bisherigen Stand.
 */
class ForcedReloadTest {

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

    private void firstReadIn() {
        syncs.provisionScoped(context(), "wiki", "DOC", headers -> header -> true, null, false);
        assertThat(fetched).containsExactlyInAnyOrder("1", "2", "3");
        fetched.clear();
    }

    @Test
    void anOrdinaryReadInLoadsNothingThatIsUnchanged() {
        firstReadIn();

        syncs.provisionScoped(context(), "wiki", "DOC", headers -> header -> true, null, false);

        assertThat(fetched).isEmpty();
    }

    @Test
    void forcingReloadsEveryPageIntoANewGeneration() {
        firstReadIn();
        long before = store.confirmed("wiki", "DOC").orElseThrow().generation();

        syncs.provisionScoped(context(), "wiki", "DOC", headers -> header -> true, null, true);

        assertThat(fetched).containsExactlyInAnyOrder("1", "2", "3");
        SyncBaseline after = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(after.generation()).isEqualTo(before + 1);
        assertThat(after.stalePageIds()).isEmpty();
    }

    @Test
    void aPageWhoseForcedReloadFailsKeepsItsPreviousState() {
        firstReadIn();
        failing.add("2");

        syncs.provisionScoped(context(), "wiki", "DOC", headers -> header -> true, null, true);

        SyncBaseline after = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(after.pages()).containsKey("2");
        assertThat(after.stalePageIds()).containsExactly("2");
        assertThat(store.repository.find(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "2")))
                .isPresent();
    }

    @Test
    void forcingASubtreeReloadsOnlyTheSubtree() {
        source.put("4", "DOC", null, "1", null);
        firstReadInAll();

        syncs.provisionScoped(context(), "wiki", "DOC", headers -> header -> true,
                headers -> header -> !header.key().pageId().equals("4"), true);

        assertThat(fetched).containsExactlyInAnyOrder("1", "2", "3");
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().stalePageIds()).doesNotContain("4");
    }

    private void firstReadInAll() {
        syncs.provisionScoped(context(), "wiki", "DOC", headers -> header -> true, null, false);
        fetched.clear();
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
        };
    }
}
