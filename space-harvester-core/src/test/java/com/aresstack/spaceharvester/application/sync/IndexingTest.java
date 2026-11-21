package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.files.FileIndexMarks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Indexieren (Angelo 20.11.2026): Unter einer markierten Wurzel lädt jeder Abgleich vollständig, auch in der
 * Betriebsart „nur katalogisieren“; außerhalb bleibt es bei der Betriebsart.
 */
class IndexingTest {

    /** 1 mit Unterseiten 2 und 4 (unter 2), daneben 3. */
    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "1", null)
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "1", null)
            .put("4", "DOC", "2", "1", null);
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final InMemorySyncStore store = new InMemorySyncStore(repository);
    private final Set<String> fetched = new HashSet<>();
    private final SpaceSync sync = new SpaceSync(source.queries(), store, FakeSource.CLOCK);
    private final PlanApplier applier = new PlanApplier((sourceId, pageId) -> {
        fetched.add(pageId);
        return snapshot(source.pageMetadata(pageId));
    });

    @TempDir
    Path directory;

    private static PageSnapshot snapshot(PageMetadata page) {
        return PageSnapshot.of("wiki", page, new NativePayload(("{\"id\":\"" + page.pageId() + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    }

    @Test
    void aMarkedSubtreeIsLoadedCompletelyAndTheRestOnlyCatalogued() {
        FileIndexMarks marks = new FileIndexMarks(directory);
        marks.mark("wiki", "DOC", "2");
        sync.indexMarks(marks);

        SpaceSync.SyncRun run = sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.CATALOG_ONLY);

        assertThat(run.plan().changes()).extracting(PageChange::pageId, PageChange::kind).containsExactly(
                tuple("1", PageChange.Kind.NOT_LOADED),
                tuple("2", PageChange.Kind.NEW),
                tuple("3", PageChange.Kind.NOT_LOADED),
                tuple("4", PageChange.Kind.NEW));
        assertThat(fetched).containsExactlyInAnyOrder("2", "4");
        assertThat(run.confirmed().pages()).containsOnlyKeys("2", "4");
    }

    @Test
    void aMarkedSpaceLoadsEveryPageAndAnOutdatedMarkedPageIsReloaded() {
        FileIndexMarks marks = new FileIndexMarks(directory);
        sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.FULL);
        source.put("3", "DOC", null, "2", null);
        fetched.clear();
        marks.mark("wiki", "DOC", null);
        sync.indexMarks(marks);

        SpaceSync.SyncRun run = sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.CATALOG_ONLY);

        assertThat(run.plan().changes()).extracting(PageChange::pageId, PageChange::kind)
                .contains(tuple("3", PageChange.Kind.CONTENT_CHANGED));
        assertThat(fetched).containsExactly("3");
    }

    @Test
    void marksSurviveARestartAndOnlyTheExactRootIsUnmarked() {
        FileIndexMarks marks = new FileIndexMarks(directory);
        marks.mark("wiki", "DOC", null);
        marks.mark("wiki", "DOC", "2");
        marks.mark("bscw-bund", "bscw-bund", "aHR0cHM6Ly9ic2N3");

        FileIndexMarks reopened = new FileIndexMarks(directory);
        assertThat(reopened.roots("wiki", "DOC")).containsExactlyInAnyOrder(IndexMarks.WHOLE_SPACE, "2");
        assertThat(reopened.marked("bscw-bund", "bscw-bund", "aHR0cHM6Ly9ic2N3")).isTrue();

        reopened.unmark("wiki", "DOC", null);
        assertThat(new FileIndexMarks(directory).roots("wiki", "DOC")).containsExactly("2");
        assertThat(reopened.roots("wiki", "OTHER")).isEmpty();
    }

    @Test
    void theScopeFollowsAncestorsAndIgnoresBrokenOrCyclicChains() {
        Map<String, String> parents = Map.of("4", "2", "2", "1", "5", "6", "6", "5");
        var scope = IndexScope.of(Set.of("2"), parents::get);

        assertThat(scope.test("4")).isTrue();
        assertThat(scope.test("2")).isTrue();
        assertThat(scope.test("1")).isFalse();
        assertThat(scope.test("5")).isFalse();
        assertThat(scope.test("unknown")).isFalse();
        assertThat(IndexScope.of(Set.of(), parents::get).test("2")).isFalse();
        assertThat(IndexScope.of(Set.of(IndexMarks.WHOLE_SPACE), parents::get).test("unknown")).isTrue();
    }

    @Test
    void imagesAndAttachmentsFollowANewMarkAndLaterRunsThatLoadedPages() {
        FileIndexMarks marks = new FileIndexMarks(directory);
        java.util.List<String> collected = new java.util.ArrayList<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs.Jobs(
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), task -> { },
                new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)),
                FakeSource.CLOCK);
        Indexing indexing = new Indexing(marks, new SpaceSyncJobs(jobs, source.queries(), store,
                (sourceId, pageId) -> snapshot(source.pageMetadata(pageId)), FakeSource.CLOCK),
                (sourceId, spaceKey, pageId) -> collected.add(spaceKey + ":" + pageId));
        sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.FULL);

        // Neu markiert: Der gestartete Abgleich holt Bilder und Anhänge auch ohne geladene Seite.
        assertThat(indexing.index("wiki", "DOC", "2", "owner", SyncMode.CATALOG_ONLY)).isNotNull();
        assertThat(marks.marked("wiki", "DOC", "2")).isTrue();
        indexing.committed(sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.CATALOG_ONLY));
        assertThat(collected).containsExactly("DOC:2");

        // Danach ohne geladene Seite: nichts.
        collected.clear();
        indexing.committed(sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.CATALOG_ONLY));
        assertThat(collected).isEmpty();

        source.put("4", "DOC", "2", "2", null);
        sync.indexMarks(marks);
        indexing.committed(sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.CATALOG_ONLY));
        assertThat(collected).containsExactly("DOC:2");
    }
}
