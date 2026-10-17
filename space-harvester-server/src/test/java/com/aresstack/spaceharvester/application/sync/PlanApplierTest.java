package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Ganze Läufe gegen das {@code SnapshotRepository}: Inhalt nur bei Bedarf und nur passend zur Inventur, Kopfdaten ohne
 * Body, sichere Entfernung, und nichts wird sichtbar ohne bestätigte Generation.
 */
class PlanApplierTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final InMemorySyncStore store = new InMemorySyncStore(repository);
    private final Set<String> fetched = new HashSet<>();
    private final Set<String> fetchFailures = new HashSet<>();
    /** Was die Quelle beim Inhaltsabruf liefert, wenn es von der Listung abweicht. */
    private final Map<String, PageMetadata> fetchAnswers = new HashMap<>();
    private String crashAtFetchOf;
    private final SpaceSync sync = new SpaceSync(source.queries(), store, FakeSource.CLOCK);
    private final PlanApplier applier = new PlanApplier((sourceId, pageId) -> {
        if (pageId.equals(crashAtFetchOf)) {
            throw new IllegalStateException("Absturz beim Laden");
        }
        if (fetchFailures.contains(pageId)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                    null);
        }
        fetched.add(pageId);
        PageMetadata page = fetchAnswers.getOrDefault(pageId, source.pageMetadata(pageId));
        return PageSnapshot.of(sourceId, page, new NativePayload(
                ("{\"id\":\"" + pageId + "\",\"version\":\"" + page.version() + "\"}").getBytes(StandardCharsets.UTF_8),
                "application/json", "confluence-content/v1"), FakeSource.CLOCK.instant());
    });

    private SpaceSync.SyncRun run() {
        fetched.clear();
        return sync.run("wiki", "DOC", applier);
    }

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    private String revision(String pageId) {
        return repository.find(key(pageId)).orElseThrow().revision();
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
        assertThat(revision("3")).isEqualTo("8");
    }

    @Test
    void anOlderAnswerThanTheInventoryIsNotStoredAndNotConfirmed() {
        run();
        source.put("3", "DOC", null, "8", null);
        fetchAnswers.put("3", FakeSource.page("3", "DOC", null, "7", null));

        SpaceSync.SyncRun lagging = run();

        assertThat(lagging.failedPageIds()).containsExactly("3");
        assertThat(lagging.confirmed().pages().get("3").version()).isEqualTo("7");
        assertThat(lagging.confirmed().stalePageIds()).containsExactly("3");
        assertThat(revision("3")).isEqualTo("7");

        fetchAnswers.clear();
        run();

        assertThat(fetched).containsExactly("3");
        assertThat(revision("3")).isEqualTo("8");
    }

    @Test
    void aNewerAnswerThanTheInventoryIsNotStoredAndTheNextRunCatchesUp() {
        run();
        source.put("3", "DOC", null, "8", null);
        fetchAnswers.put("3", FakeSource.page("3", "DOC", null, "9", null));

        SpaceSync.SyncRun ahead = run();

        assertThat(ahead.failedPageIds()).containsExactly("3");
        assertThat(revision("3")).isEqualTo("7");

        fetchAnswers.clear();
        source.put("3", "DOC", null, "9", null);
        run();

        assertThat(revision("3")).isEqualTo("9");
    }

    @Test
    void anAnswerFromAnotherSpaceIsNotStored() {
        source.put("4", "DOC", null, "1", null);
        fetchAnswers.put("4", FakeSource.page("4", "OPS", null, "1", null));

        SpaceSync.SyncRun run = run();

        assertThat(run.failedPageIds()).containsExactly("4");
        assertThat(run.confirmed().pages()).doesNotContainKey("4");
        assertThat(repository.find(key("4"))).isEmpty();
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
    void anAbortedInventoryFetchesAndWritesNothing() {
        run();
        source.remove("3").put("4", "DOC", null, "1", null).put("1", "DOC", null, "4", "api");
        source.failListingAtCall(0);

        SpaceSync.SyncRun aborted = run();

        assertThat(aborted.confirmed()).isNull();
        assertThat(aborted.notConfirmed()).contains("INCOMPLETE");
        assertThat(fetched).isEmpty();
        assertThat(repository.find(key("3"))).isPresent();
        assertThat(repository.find(key("4"))).isEmpty();
        assertThat(revision("1")).isEqualTo("3");
    }

    @Test
    void aCrashAfterStagedWritesLeavesTheCacheAtTheConfirmedGeneration() {
        run();
        source.remove("2").put("1", "DOC", null, "4", "api").put("3", "DOC", null, "8", null);
        crashAtFetchOf = "3";

        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);

        assertThat(fetched).contains("1");
        assertThat(revision("1")).isEqualTo("3");
        assertThat(repository.find(key("2"))).isPresent();
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().generation()).isEqualTo(1);
    }

    @Test
    void aLostCommitLeavesTheCacheAtTheConfirmedGeneration() {
        run();
        source.remove("2").put("3", "DOC", null, "8", null);
        store.failNextCommit = true;

        assertThatThrownBy(this::run).isInstanceOf(SyncConflictException.class);

        assertThat(fetched).containsExactly("3");
        assertThat(revision("3")).isEqualTo("7");
        assertThat(repository.find(key("2"))).isPresent();
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().generation()).isEqualTo(1);
    }

    @Test
    void aFailedFetchKeepsTheOldSnapshotAndIsRetried() {
        run();
        source.put("3", "DOC", null, "8", null);
        fetchFailures.add("3");

        SpaceSync.SyncRun failed = run();

        assertThat(failed.failedPageIds()).containsExactly("3");
        assertThat(failed.confirmed().stalePageIds()).containsExactly("3");
        assertThat(revision("3")).isEqualTo("7");

        fetchFailures.clear();
        run();

        assertThat(fetched).containsExactly("3");
        assertThat(revision("3")).isEqualTo("8");
    }
}
