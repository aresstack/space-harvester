package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ADR 0014, Punkt 2: Ohne bestätigte Generation gilt als bekannt, was im Cache liegt. Der Abgleich lädt bekannte Seiten
 * nur bei neuer Version und unbekannte nur, wenn der Space vollständig gehalten werden soll.
 */
class ReconcileWithoutFirstLoadTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final InMemorySyncStore store = new InMemorySyncStore(repository);
    private final Set<String> fetched = new HashSet<>();
    private final SpaceSync sync = new SpaceSync(source.queries(), store, FakeSource.CLOCK);
    private final PlanApplier applier = new PlanApplier((sourceId, pageId) -> {
        fetched.add(pageId);
        return snapshot(source.pageMetadata(pageId));
    });

    /** Wie durch Suche oder Abruf in den Cache gelangt, ohne Generation. */
    private void cached(String pageId, String space, String parent, String version) {
        repository.save(snapshot(FakeSource.page(pageId, space, parent, version, pageId.equals("1") ? "api" : null)));
    }

    private static PageSnapshot snapshot(PageMetadata page) {
        return PageSnapshot.of("wiki", page, new NativePayload(("{\"id\":\"" + page.pageId() + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    }

    @Test
    void cachedPagesWithTheSameVersionAreNotFetchedAgainAndUnknownOnesAreNotLoaded() {
        cached("1", "DOC", null, "3");
        cached("3", "DOC", null, "6");

        SpaceSync.SyncRun run = sync.run("wiki", "DOC", applier, SyncListener.NONE, false);

        assertThat(run.plan().changes()).extracting(PageChange::pageId, PageChange::kind).containsExactly(
                tuple("1", PageChange.Kind.UNCHANGED),
                tuple("2", PageChange.Kind.NOT_LOADED),
                tuple("3", PageChange.Kind.CONTENT_CHANGED));
        assertThat(fetched).containsExactly("3");
        assertThat(run.failedPageIds()).isEmpty();
        assertThat(run.confirmed().pages()).containsOnlyKeys("1", "3");
        assertThat(run.confirmed().missingPageIds()).containsExactly("2");
        assertThat(run.confirmed().complete()).isFalse();
        assertThat(run.summary().notLoaded()).isEqualTo(1);
        assertThat(repository.find(new PageKey("wiki", "2"))).isEmpty();
        assertThat(repository.findHeader(new PageKey("wiki", "2")).orElseThrow().metadata().parentPageId())
                .isEqualTo("1");
    }

    @Test
    void aCachedPageTheSourceNoLongerListsIsCheckedAndWithheld() {
        cached("1", "DOC", null, "3");
        cached("9", "DOC", null, "1");

        SpaceSync.SyncRun run = sync.run("wiki", "DOC", applier, SyncListener.NONE, false);

        assertThat(run.plan().removals().get("9").verdict()).isEqualTo(RemovalCheck.Verdict.INACCESSIBLE);
        assertThat(run.confirmed().withheldPageIds()).containsOnlyKeys("9");
        assertThat(repository.find(new PageKey("wiki", "9"))).isEmpty();
    }

    @Test
    void aSpaceKeptCompleteLoadsOnlyWhatTheCacheDoesNotAlreadyHave() {
        cached("1", "DOC", null, "3");

        SpaceSync.SyncRun run = sync.run("wiki", "DOC", applier);

        assertThat(fetched).containsExactlyInAnyOrder("2", "3");
        assertThat(run.confirmed().complete()).isTrue();
        assertThat(run.summary().notLoaded()).isZero();
    }

    @Test
    void pagesLoadedBySearchAfterAReconcileAreKnownToTheNextOne() {
        sync.run("wiki", "DOC", applier, SyncListener.NONE, false);
        cached("2", "DOC", "1", "1");
        fetched.clear();

        SpaceSync.SyncRun next = sync.run("wiki", "DOC", applier, SyncListener.NONE, false);

        assertThat(fetched).isEmpty();
        assertThat(next.confirmed().pages()).containsOnlyKeys("2");
        assertThat(next.confirmed().missingPageIds()).containsExactly("1", "3");
    }
}
