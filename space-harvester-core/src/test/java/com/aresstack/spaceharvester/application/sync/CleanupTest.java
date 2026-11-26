package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.files.FileIndexMarks;
import com.aresstack.spaceharvester.persistence.files.FileNavigationMarks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * „Bereinigen“ (ADR 0037, Punkt 3; Angelo 26.11.2026): Nur Seiten ohne jeden Erwerbsgrund kommen in Frage, nie solche in
 * einem unvollständig erfassten Bereich; entfernt wird über dieselbe Generation wie bei jedem Abgleich.
 */
class CleanupTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "1", null)
            .put("2", "DOC", null, "1", null)
            .put("3", "DOC", null, "1", null)
            .put("4", "DOC", null, "1", null)
            .put("5", "DOC", null, "1", null);
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final InMemorySyncStore store = new InMemorySyncStore(repository);
    private final SpaceSync sync = new SpaceSync(source.queries(), store, FakeSource.CLOCK);
    private final PlanApplier applier = new PlanApplier((sourceId, pageId) -> snapshot(source.pageMetadata(pageId)));

    @TempDir
    Path directory;

    private static PageSnapshot snapshot(PageMetadata page) {
        return PageSnapshot.of("wiki", page, new NativePayload(("{\"id\":\"" + page.pageId() + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    }

    /** 2 ohne Grund; 3 indexiert; 4 über die Navigation; 5 in einem unvollständigen Bereich; 1 gelistet. */
    private void outsideTheInventory() {
        sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.FULL);
        for (String pageId : List.of("2", "3", "4")) {
            source.remove(pageId);
            source.outsideScope.add(pageId);
        }
        source.remove("5");
        source.uncovered.add("5");
        FileIndexMarks index = new FileIndexMarks(directory);
        index.mark("wiki", "DOC", "3");
        sync.indexMarks(index);
        FileNavigationMarks navigation = new FileNavigationMarks(directory);
        navigation.mark("wiki", "DOC", List.of("4"));
        sync.navigationMarks(navigation);
    }

    @Test
    void onlyPagesOutsideTheScopeWithoutAnyReasonAreCandidates() {
        outsideTheInventory();

        SyncPlan plan = sync.cleanupPlan("wiki", "DOC", SyncListener.NONE);

        assertThat(sync.withoutReason(plan)).containsExactly("2");
    }

    @Test
    void cleaningRemovesOnlyChosenPagesThatStillHaveNoReasonAndKeepsTheRest() {
        outsideTheInventory();

        SpaceSync.SyncRun run = sync.cleanUp("wiki", "DOC", applier, SyncListener.NONE, Set.of("2", "3", "4", "5"));

        assertThat(run.plan().purges()).containsExactly("2");
        assertThat(run.confirmed().pages()).doesNotContainKey("2").containsKeys("1", "3", "4", "5");
        assertThat(repository.find(new PageKey("wiki", "2"))).isEmpty();
        assertThat(repository.find(new PageKey("wiki", "3"))).isPresent();
        assertThat(repository.find(new PageKey("wiki", "5"))).isPresent();
        // Der nächste Abgleich lässt alles Übrige stehen.
        SpaceSync.SyncRun next = sync.run("wiki", "DOC", applier, SyncListener.NONE, SyncMode.SYNC_CACHE);
        assertThat(next.confirmed().pages()).containsKeys("1", "3", "4", "5");
    }

    @Test
    void withoutAConfirmedInventoryNothingIsACandidateAndNothingIsRemoved() {
        outsideTheInventory();
        source.tamper = batch -> {
            throw new com.aresstack.spaceharvester.application.sources.SourceAccessException("wiki",
                    com.aresstack.spaceharvester.application.sources.SourceAccessException.Reason.ACCESS_DENIED,
                    "Anmeldung abgelehnt", null);
        };

        SpaceSync.SyncRun run = sync.cleanUp("wiki", "DOC", applier, SyncListener.NONE, Set.of("2"));

        assertThat(run.confirmed()).isNull();
        assertThat(repository.find(new PageKey("wiki", "2"))).isPresent();
    }

    @Test
    void theFirstPreviewRecordsTheUnknownReasonsAndLaterOnlyThoseStayUnknown() {
        FileNavigationMarks marks = new FileNavigationMarks(directory);

        assertThat(marks.unknown("wiki", "DOC", Set.of("2", "7"))).containsExactlyInAnyOrder("2", "7");
        assertThat(new FileNavigationMarks(directory).unknown("wiki", "DOC", Set.of("2", "8")))
                .containsExactly("2");
        marks.forgetUnknown("wiki", "DOC", List.of("2"));
        assertThat(new FileNavigationMarks(directory).unknown("wiki", "DOC", Set.of("2", "8"))).isEmpty();
        assertThat(new FileNavigationMarks(directory).unknown("wiki", "OTHER", Set.of("9"))).containsExactly("9");
    }
}
