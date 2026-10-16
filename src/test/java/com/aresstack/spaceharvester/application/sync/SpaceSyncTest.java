package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ablauf über mehrere Läufe: erste Ernte, Wiederholung, Änderungen, Umzug, Löschung, Abbruch und Fehlschläge. */
class SpaceSyncTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemoryStore store = new InMemoryStore();
    private final SpaceSync sync = new SpaceSync(source.queries(), store, FakeSource.CLOCK);

    private SyncBaseline firstRun() {
        return sync.confirm(sync.plan("wiki", "DOC"), Set.of());
    }

    @Test
    void firstRunFetchesEverythingAndConfirmsGenerationOne() {
        SyncPlan plan = sync.plan("wiki", "DOC");

        assertThat(plan.changes()).extracting(PageChange::kind).containsOnly(PageChange.Kind.NEW);
        assertThat(plan.contentToFetch()).hasSize(3);

        SyncBaseline baseline = sync.confirm(plan, Set.of());

        assertThat(baseline.generation()).isEqualTo(1);
        assertThat(baseline.pages()).containsOnlyKeys("1", "2", "3");
        assertThat(baseline.stalePageIds()).isEmpty();
        assertThat(baseline.spaceFingerprint()).isEqualTo(plan.inventory().spaceFingerprint().orElseThrow());
    }

    @Test
    void aSecondRunOverAnUnchangedSpaceSkipsEverything() {
        firstRun();

        SyncPlan plan = sync.plan("wiki", "DOC");

        assertThat(plan.upToDate()).isTrue();
        assertThat(plan.changes()).extracting(PageChange::kind).containsOnly(PageChange.Kind.UNCHANGED);
        assertThat(plan.contentToFetch()).isEmpty();
        assertThat(sync.confirm(plan, Set.of()).generation()).isEqualTo(2);
    }

    @Test
    void aLabelOnlyChangeUpdatesAttributesWithoutFetchingContent() {
        firstRun();
        source.put("1", "DOC", null, "3", "api,veraltet");

        SyncPlan plan = sync.plan("wiki", "DOC");

        assertThat(plan.upToDate()).isFalse();
        PageChange change = only(plan, "1");
        assertThat(change.kind()).isEqualTo(PageChange.Kind.ATTRIBUTES_CHANGED);
        assertThat(change.requiresContent()).isFalse();
        assertThat(change.current().metadataHash()).isEqualTo(change.previous().metadataHash());
        assertThat(plan.contentToFetch()).isEmpty();
    }

    @Test
    void aNewVersionFetchesContent() {
        firstRun();
        source.put("3", "DOC", null, "8", null);

        PageChange change = only(sync.plan("wiki", "DOC"), "3");

        assertThat(change.kind()).isEqualTo(PageChange.Kind.CONTENT_CHANGED);
        assertThat(change.requiresContent()).isTrue();
    }

    @Test
    void aMoveWithoutNewVersionUpdatesOnlyMetadata() {
        firstRun();
        source.put("2", "DOC", "3", "1", null);

        PageChange change = only(sync.plan("wiki", "DOC"), "2");

        assertThat(change.kind()).isEqualTo(PageChange.Kind.METADATA_CHANGED);
        assertThat(change.moved()).isTrue();
        assertThat(change.requiresContent()).isFalse();
    }

    @Test
    void anUnknownVersionWithChangedMetadataFetchesContentToBeSafe() {
        source.put("2", "DOC", "1", null, null);
        firstRun();
        source.put("2", "DOC", "3", null, null);

        assertThat(only(sync.plan("wiki", "DOC"), "2").kind()).isEqualTo(PageChange.Kind.CONTENT_CHANGED);
    }

    @Test
    void aDeletedPageIsRemovedOnlyAfterTheSourceConfirmsIt() {
        firstRun();
        source.remove("2");

        SyncPlan plan = sync.plan("wiki", "DOC");

        assertThat(only(plan, "2").kind()).isEqualTo(PageChange.Kind.MISSING);
        assertThat(plan.removals().get("2").verdict()).isEqualTo(RemovalCheck.Verdict.GONE);
        SyncBaseline next = sync.confirm(plan, Set.of());
        assertThat(next.pages()).containsOnlyKeys("1", "3");
        assertThat(next.stalePageIds()).isEmpty();
    }

    @Test
    void aPageMovedToAnotherSpaceLeavesThisSpace() {
        firstRun();
        source.put("3", "OPS", null, "7", null);

        SyncPlan plan = sync.plan("wiki", "DOC");

        RemovalCheck.Result removal = plan.removals().get("3");
        assertThat(removal.verdict()).isEqualTo(RemovalCheck.Verdict.MOVED_OUT);
        assertThat(removal.currentSpaceKey()).isEqualTo("OPS");
        assertThat(sync.confirm(plan, Set.of()).pages()).containsOnlyKeys("1", "2");
    }

    @Test
    void aMissingPageThatCannotBeVerifiedIsKeptAsStale() {
        firstRun();
        source.remove("2").failSinglePage("2", SourceAccessException.Reason.UNAVAILABLE);

        SyncPlan plan = sync.plan("wiki", "DOC");

        assertThat(plan.removals().get("2").verdict()).isEqualTo(RemovalCheck.Verdict.UNVERIFIED);
        SyncBaseline next = sync.confirm(plan, Set.of());
        assertThat(next.pages()).containsKey("2");
        assertThat(next.stalePageIds()).containsExactly("2");
    }

    @Test
    void aPageTheListingMissedButTheSourceStillHasBlocksTheGeneration() {
        firstRun();
        source.tamper = batch -> new PageMetadataBatch(
                batch.pages().stream().filter(page -> !page.pageId().equals("2")).toList(), batch.nextCursor());

        SyncPlan plan = sync.plan("wiki", "DOC");

        assertThat(plan.removals().get("2").verdict()).isEqualTo(RemovalCheck.Verdict.STILL_PRESENT);
        assertThatThrownBy(() -> sync.confirm(plan, Set.of())).isInstanceOf(UnconfirmableSyncException.class);
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().generation()).isEqualTo(1);
    }

    @Test
    void anAbortedRunRemovesNothingConfirmsNothingAndCanSimplyStartOver() {
        source.put("4", "DOC", "3", "1", null).put("5", "DOC", "3", "1", null);
        SyncBaseline first = firstRun();
        source.remove("5");
        source.failListingAtCall(0);

        SyncPlan aborted = sync.plan("wiki", "DOC");

        assertThat(aborted.inventory().status()).isEqualTo(Inventory.Status.INCOMPLETE);
        assertThat(aborted.changes()).extracting(PageChange::kind).containsOnly(PageChange.Kind.NOT_SEEN);
        assertThat(aborted.removals()).isEmpty();
        assertThatThrownBy(() -> sync.confirm(aborted, Set.of())).isInstanceOf(UnconfirmableSyncException.class);
        assertThat(store.confirmed("wiki", "DOC")).contains(first);

        source.failListingAtCall(-1);
        SyncPlan retry = sync.plan("wiki", "DOC");
        assertThat(only(retry, "5").kind()).isEqualTo(PageChange.Kind.MISSING);
        assertThat(sync.confirm(retry, Set.of()).pages()).containsOnlyKeys("1", "2", "3", "4");
    }

    @Test
    void aFailedContentFetchKeepsThePreviousStateAndIsRetriedNextRun() {
        firstRun();
        source.put("3", "DOC", null, "8", null).put("4", "DOC", null, "1", null);

        SyncBaseline next = sync.confirm(sync.plan("wiki", "DOC"), Set.of("3", "4"));

        assertThat(next.pages().get("3").version()).isEqualTo("7");
        assertThat(next.pages()).doesNotContainKey("4");
        assertThat(next.stalePageIds()).containsExactly("3");

        SyncPlan retry = sync.plan("wiki", "DOC");
        assertThat(retry.upToDate()).isFalse();
        assertThat(retry.contentToFetch()).extracting(PageChange::pageId).containsExactly("3", "4");
        assertThat(sync.confirm(retry, Set.of()).stalePageIds()).isEmpty();
    }

    @Test
    void failuresForPagesOutsideThePlanAreRejected() {
        SyncPlan plan = sync.plan("wiki", "DOC");

        assertThatThrownBy(() -> plan.nextBaseline(FakeSource.CLOCK.instant(), Set.of("99")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aParallelRunThatConfirmedFirstWins() {
        firstRun();
        SyncPlan slow = sync.plan("wiki", "DOC");
        sync.confirm(sync.plan("wiki", "DOC"), Set.of());

        assertThatThrownBy(() -> sync.confirm(slow, Set.of())).isInstanceOf(SyncConflictException.class);
    }

    private static PageChange only(SyncPlan plan, String pageId) {
        List<PageChange> changed = plan.changes().stream()
                .filter(change -> change.kind() != PageChange.Kind.UNCHANGED).toList();
        assertThat(changed).extracting(PageChange::pageId).containsExactly(pageId);
        return changed.get(0);
    }

    /** Fake für den Port; die Lucene-Umsetzung (#26) muss dieselben Regeln erfüllen. */
    static final class InMemoryStore implements SyncBaselineStore {

        private final Map<String, SyncBaseline> baselines = new HashMap<>();

        @Override
        public Optional<SyncBaseline> confirmed(String sourceId, String spaceKey) {
            return Optional.ofNullable(baselines.get(sourceId + "/" + spaceKey));
        }

        @Override
        public synchronized void publish(SyncBaseline next) {
            long current = confirmed(next.sourceId(), next.spaceKey()).map(SyncBaseline::generation).orElse(0L);
            if (next.generation() != current + 1) {
                throw new SyncConflictException("Generation " + next.generation() + " folgt nicht auf " + current);
            }
            baselines.put(next.sourceId() + "/" + next.spaceKey(), next);
        }
    }
}
