package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ganze Läufe gegen das {@code SnapshotRepository}: Inhalt nur bei Bedarf, Kopfdaten ohne Body, sichere Entfernung. */
class PlanApplierTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final Set<String> fetched = new HashSet<>();
    private final Set<String> fetchFailures = new HashSet<>();
    private final SpaceSync sync = new SpaceSync(source.queries(), new SpaceSyncTest.InMemoryStore(),
            FakeSource.CLOCK);
    private final PlanApplier applier = new PlanApplier(repository, (sourceId, pageId) -> {
        if (fetchFailures.contains(pageId)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                    null);
        }
        fetched.add(pageId);
        return PageSnapshot.of(sourceId, source.pageMetadata(pageId), new NativePayload(
                ("{\"id\":\"" + pageId + "\"}").getBytes(StandardCharsets.UTF_8), "application/json",
                "confluence-content/v1"), FakeSource.CLOCK.instant());
    });

    private SpaceSync.SyncRun run() {
        fetched.clear();
        return sync.run("wiki", "DOC", applier);
    }

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    @Test
    void firstRunStoresAllSnapshotsAndASecondRunFetchesNothing() {
        SpaceSync.SyncRun first = run();

        assertThat(first.confirmed().generation()).isEqualTo(1);
        assertThat(fetched).containsExactlyInAnyOrder("1", "2", "3");
        assertThat(repository.find(key("2"))).isPresent();

        SpaceSync.SyncRun second = run();

        assertThat(second.confirmed().generation()).isEqualTo(2);
        assertThat(fetched).isEmpty();
    }

    @Test
    void aLabelChangeReplacesOnlyTheCurrentHeader() {
        run();
        PageSnapshot before = repository.find(key("1")).orElseThrow();
        source.put("1", "DOC", null, "3", "api,veraltet");

        run();

        assertThat(fetched).isEmpty();
        assertThat(repository.find(key("1"))).contains(before);
        assertThat(repository.findHeader(key("1")).orElseThrow().metadata().attributes())
                .containsEntry("confluence.labels", "api,veraltet");
    }

    @Test
    void aNewVersionStoresANewSnapshot() {
        run();
        source.put("3", "DOC", null, "8", null);

        run();

        assertThat(fetched).containsExactly("3");
        assertThat(repository.find(key("3")).orElseThrow().revision()).isEqualTo("8");
    }

    @Test
    void aConfirmedDeletionRemovesTheSnapshot() {
        run();
        source.remove("2");

        SpaceSync.SyncRun run = run();

        assertThat(run.confirmed()).isNotNull();
        assertThat(repository.find(key("2"))).isEmpty();
        assertThat(repository.findHeader(key("2"))).isEmpty();
    }

    @Test
    void aPageMovedToAnotherSpaceIsKeptWithItsNewHeader() {
        run();
        source.put("3", "OPS", null, "7", null);

        run();

        assertThat(repository.find(key("3"))).isPresent();
        assertThat(repository.findHeader(key("3")).orElseThrow().metadata().partitionKey()).isEqualTo("OPS");
    }

    @Test
    void anAbortedRunUpdatesWhatItReadButRemovesAndConfirmsNothing() {
        run();
        source.remove("3").put("4", "DOC", null, "1", null);
        source.failListingAtCall(0);

        SpaceSync.SyncRun aborted = run();

        assertThat(aborted.confirmed()).isNull();
        assertThat(aborted.notConfirmed()).contains("INCOMPLETE");
        assertThat(repository.find(key("3"))).isPresent();
    }

    @Test
    void aFailedFetchKeepsTheOldSnapshotAndIsRetried() {
        run();
        source.put("3", "DOC", null, "8", null);
        fetchFailures.add("3");

        SpaceSync.SyncRun failed = run();

        assertThat(failed.failedPageIds()).containsExactly("3");
        assertThat(failed.confirmed().stalePageIds()).containsExactly("3");
        assertThat(repository.find(key("3")).orElseThrow().revision()).isEqualTo("7");

        fetchFailures.clear();
        run();

        assertThat(fetched).containsExactly("3");
        assertThat(repository.find(key("3")).orElseThrow().revision()).isEqualTo("8");
    }
}
