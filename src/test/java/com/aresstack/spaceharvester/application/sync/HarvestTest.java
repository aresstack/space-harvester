package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Ernte eines Space in den Cache (#10): der erste Abgleich auf leerem Cache. Vollständig über alle Wurzeln, tiefe und
 * breite Bäume über viele Listungsstapel, doppelt gelieferte Seiten und einzelne fehlende Seiten; der Baum entsteht
 * danach allein aus dem Cache.
 */
class HarvestTest {

    private static final int DEPTH = 300;
    private static final int WIDTH = 1000;

    private final FakeSource source = new FakeSource();
    private final InMemorySyncStore store = new InMemorySyncStore();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run,
            new JobLimits(1, 5, Duration.ofHours(1)), FakeSource.CLOCK);
    private final Set<String> unreachable = new HashSet<>();
    private final List<String> fetched = new ArrayList<>();
    private final PageContentFetcher fetcher = (sourceId, pageId) -> {
        if (unreachable.contains(pageId)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                    null);
        }
        fetched.add(pageId);
        PageMetadata page = source.pageMetadata(pageId);
        return PageSnapshot.of(sourceId, page, new NativePayload(("{\"id\":\"" + pageId + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    };
    private final SpaceSyncJobs harvests = new SpaceSyncJobs(jobs, source.queries(), store, fetcher,
            FakeSource.CLOCK);

    /**
     * Drei Wurzeln: die Startseite {@code home} mit einer Kette der Tiefe {@value #DEPTH}, eine zweite Wurzel mit
     * {@value #WIDTH} Kindern und eine einzelne Seite ohne Eltern. Die Quelle liefert sie gemischt, nicht in Baumordnung.
     */
    private int space() {
        source.put("wide", "DOC", null, "1", null);
        for (int i = WIDTH - 1; i >= 0; i--) {
            source.put("w" + i, "DOC", "wide", "1", null);
        }
        for (int i = DEPTH - 1; i >= 1; i--) {
            source.put("d" + i, "DOC", i == 1 ? "home" : "d" + (i - 1), "1", null);
        }
        source.put("home", "DOC", null, "1", null);
        source.put("lonely", "DOC", null, "1", null);
        source.put("elsewhere", "OTHER", null, "1", null);
        return 1 + WIDTH + (DEPTH - 1) + 1 + 1;
    }

    private JobStatus harvest() {
        JobStatus started = harvests.start("wiki", "DOC", "TECHNICAL_CLIENT:etl");
        return jobs.status(started.id());
    }

    /** Baum nur aus dem bestätigten Cache-Stand, ohne die Quelle. */
    private PageTree cachedTree() {
        return PageTreeBuilder.build(store.confirmed("wiki", "DOC").orElseThrow().pages().values().stream()
                .map(entry -> new TreeEntry(entry.pageId(), entry.parentPageId())).toList());
    }

    @Test
    void harvestsEveryRootDeepAndWideBranchesAcrossManyBatches() {
        int pages = space();

        JobStatus done = harvest();

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).isEqualTo(new JobProgress(pages, pages, pages, 0));
        SyncBaseline baseline = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(baseline.generation()).isEqualTo(1);
        assertThat(baseline.complete()).isTrue();
        assertThat(baseline.pages()).hasSize(pages).doesNotContainKey("elsewhere");
        assertThat(fetched).hasSize(pages);
        for (String pageId : List.of("home", "d" + (DEPTH - 1), "wide", "w0", "w" + (WIDTH - 1), "lonely")) {
            assertThat(store.repository.find(new PageKey("wiki", pageId))).as(pageId).isPresent();
        }

        int fetchesBefore = fetched.size();
        PageTree tree = cachedTree();
        assertThat(fetched).hasSize(fetchesBefore);
        assertThat(tree.roots()).extracting(PageTree.Node::pageId).containsExactlyInAnyOrder("home", "wide", "lonely");
        assertThat(tree.nodeCount()).isEqualTo(pages);
        assertThat(tree.depth()).isEqualTo(DEPTH);
        assertThat(tree.anomalies()).isEmpty();
        PageTree.Node wide = tree.roots().stream().filter(node -> node.pageId().equals("wide")).findFirst()
                .orElseThrow();
        assertThat(wide.children()).hasSize(WIDTH);
    }

    @Test
    void pagesDeliveredTwiceAreHarvestedOnce() {
        int pages = space();
        // Jede Seite eines Stapels kommt zweimal, wie bei einer Quelle, die sich beim Blättern überschneidet.
        source.tamper = batch -> {
            List<PageMetadata> doubled = new ArrayList<>(batch.pages());
            doubled.addAll(batch.pages());
            return new PageMetadataBatch(doubled, batch.nextCursor());
        };

        JobStatus done = harvest();

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().pages()).hasSize(pages);
        assertThat(fetched).hasSize(pages).doesNotHaveDuplicates();
        assertThat(cachedTree().nodeCount()).isEqualTo(pages);
    }

    @Test
    void aSinglePageThatCannotBeFetchedIsVisibleAtTheJobAndNotPassedOffAsHarvested() {
        int pages = space();
        unreachable.add("d150");

        JobStatus done = harvest();

        assertThat(done.progress().discovered()).isEqualTo(pages);
        assertThat(done.progress().failed()).isEqualTo(1);
        assertThat(done.progress().written()).isEqualTo(pages - 1);
        SyncSummary summary = harvests.status(done.id(), "TECHNICAL_CLIENT:etl").summary();
        assertThat(summary.failedPageIds()).containsExactly("d150");
        assertThat(store.repository.find(new PageKey("wiki", "d150"))).isEmpty();
        // Der Rest ist sichtbar, aber die Ernte gilt nicht als vollständig.
        assertThat(done.state()).isEqualTo(JobState.FAILED);
        assertThat(done.error().code()).isEqualTo(SpaceSyncJobs.PARTIAL);
        SyncBaseline baseline = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(baseline.missingPageIds()).containsExactly("d150");
        assertThat(baseline.complete()).isFalse();
    }

    @Test
    void anAbortedListingHarvestsNothingAndSaysWhy() {
        space();
        source.failListingAtCall(3);

        JobStatus done = harvest();

        assertThat(done.state()).isEqualTo(JobState.FAILED);
        assertThat(done.error().code()).isEqualTo(SpaceSyncJobs.INCOMPLETE);
        assertThat(store.confirmed("wiki", "DOC")).isEmpty();
        assertThat(store.repository.find(new PageKey("wiki", "home"))).isEmpty();
    }
}
