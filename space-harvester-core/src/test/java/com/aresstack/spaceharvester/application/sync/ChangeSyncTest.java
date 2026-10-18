package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ADR 0014: Nachholen per Änderungssuche und Papierkorb statt Inventur. */
class ChangeSyncTest {

    private static final Instant AN_HOUR_AGO = FakeSource.CLOCK.instant().minus(Duration.ofHours(1));

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null)
            .listingChanges();
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final InMemorySyncStore store = new InMemorySyncStore(repository);
    private final Set<String> fetched = new HashSet<>();
    private final ChangeSync changes = new ChangeSync(source.queries(), store, FakeSource.CLOCK);
    private final PlanApplier applier = new PlanApplier((sourceId, pageId) -> {
        fetched.add(pageId);
        return snapshot(source.pageMetadata(pageId));
    });

    private static PageSnapshot snapshot(PageMetadata page) {
        return PageSnapshot.of("wiki", page, new NativePayload(("{\"id\":\"" + page.pageId() + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    }

    private void cached(String pageId) {
        repository.save(snapshot(source.pageMetadata(pageId)));
    }

    private SpaceSync.SyncRun run(Instant since) {
        return changes.run("wiki", "DOC", since, applier, SyncListener.NONE);
    }

    @Test
    void theFirstRunWithoutBaselineOnlySetsTheStartingPointAndAsksNothing() {
        cached("1");

        SpaceSync.SyncRun run = run(null);

        assertThat(source.lastWithin).isNull();
        assertThat(run.confirmed().generation()).isEqualTo(1);
        assertThat(run.confirmed().pages()).containsOnlyKeys("1");
        assertThat(run.confirmed().changesSince()).isEqualTo(FakeSource.CLOCK.instant());
    }

    @Test
    void aChangedCachedPageIsReloadedAndAChangedUnknownPageOnlyEntersTheCatalog() {
        cached("1");
        cached("3");
        source.change("1", "4", 5).change("2", "2", 5);

        SpaceSync.SyncRun run = run(AN_HOUR_AGO);

        assertThat(run.plan().inventory().status()).isEqualTo(Inventory.Status.CHANGES);
        assertThat(fetched).containsExactly("1");
        assertThat(repository.find(new PageKey("wiki", "1")).orElseThrow().metadata().version()).isEqualTo("4");
        assertThat(repository.find(new PageKey("wiki", "2"))).isEmpty();
        assertThat(repository.findHeader(new PageKey("wiki", "2")).orElseThrow().metadata().version())
                .isEqualTo("2");
        assertThat(run.confirmed().pages()).containsOnlyKeys("1", "3");
        assertThat(run.confirmed().missingPageIds()).containsExactly("2");
        assertThat(run.summary().notLoaded()).isEqualTo(1);
        assertThat(run.summary().contentChanged()).isEqualTo(1);
        assertThat(source.lastWithin).isEqualTo(Duration.ofHours(1).plus(ChangeSync.OVERLAP));
    }

    @Test
    void pagesChangedBeforeTheWindowAreLeftAlone() {
        cached("1");
        source.change("1", "4", 120);

        SpaceSync.SyncRun run = run(AN_HOUR_AGO);

        assertThat(fetched).isEmpty();
        assertThat(run.plan().changes()).isEmpty();
    }

    @Test
    void aTrashedKnownPageIsDeletedFromTheCache() {
        cached("1");
        cached("3");
        source.trash("3");

        SpaceSync.SyncRun run = run(AN_HOUR_AGO);

        assertThat(run.plan().removals().get("3").verdict()).isEqualTo(RemovalCheck.Verdict.DELETED);
        assertThat(repository.find(new PageKey("wiki", "3"))).isEmpty();
        assertThat(run.confirmed().pages()).containsOnlyKeys("1");
        assertThat(run.confirmed().withheldPageIds()).isEmpty();
        assertThat(run.summary().removed()).isEqualTo(1);
    }

    @Test
    void aTrashedPageTheCacheNeverKnewIsIgnored() {
        cached("1");
        source.trash("2");

        SpaceSync.SyncRun run = run(AN_HOUR_AGO);

        assertThat(run.plan().changes()).isEmpty();
        assertThat(run.summary().removed()).isZero();
    }

    @Test
    void aCatalogOnlyPageInTheTrashLeavesTheCatalog() {
        cached("1");
        source.change("2", "2", 5);
        run(AN_HOUR_AGO);
        source.trash("2");

        SpaceSync.SyncRun run = run(AN_HOUR_AGO);

        assertThat(run.confirmed().missingPageIds()).isEmpty();
        assertThat(repository.findHeader(new PageKey("wiki", "2"))).isEmpty();
    }

    @Test
    void aFailingSourceChangesNothing() {
        cached("1");
        SyncBaseline before = run(null).confirmed();
        source.change("1", "4", 1).failListingAtCall(0);

        SpaceSync.SyncRun run = run(null);

        assertThat(run.confirmed()).isNull();
        assertThat(run.notConfirmed()).contains("INCOMPLETE");
        assertThat(fetched).isEmpty();
        assertThat(store.confirmed("wiki", "DOC")).contains(before);
    }

    @Test
    void withoutSinceTheRunAsksFromWhereTheLastOneStarted() {
        cached("1");
        run(AN_HOUR_AGO);
        source.change("1", "4", 1);

        SpaceSync.SyncRun next = run(null);

        assertThat(source.lastWithin).isEqualTo(ChangeSync.OVERLAP);
        assertThat(fetched).containsExactly("1");
        assertThat(next.confirmed().generation()).isEqualTo(2);
    }
}
