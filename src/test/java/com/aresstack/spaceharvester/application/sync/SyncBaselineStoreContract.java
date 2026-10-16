package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Vertragstest für jede {@link SyncBaselineStore}-Umsetzung samt der {@link SnapshotRepository}-Sicht, die Leser
 * verwenden. Eine Umsetzung erbt davon und liefert einen leeren Speicher; persistente Umsetzungen überschreiben
 * zusätzlich {@link #reopen} für den Neustart.
 *
 * <p>Kern des Vertrags: Vorgemerkte Schreibvorgänge einer {@link StagedGeneration} sind für Leser unsichtbar, bis
 * {@link StagedGeneration#commit} sie zusammen mit der Generation sichtbar macht; ein Konflikt oder
 * {@link StagedGeneration#close} ohne Commit hinterlässt nichts.
 */
public abstract class SyncBaselineStoreContract {

    /** Ein Speicher: der Port für die Synchronisierung und die Lesesicht auf dieselben Daten. */
    protected record Fixture(SyncBaselineStore store, SnapshotRepository snapshots) {

        public Fixture {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(snapshots, "snapshots");
        }
    }

    /** Ein neuer, leerer Speicher. */
    protected abstract Fixture empty();

    /** Derselbe Speicher nach einem Neustart. Nicht persistente Umsetzungen geben ihn unverändert zurück. */
    protected Fixture reopen(Fixture fixture) {
        return fixture;
    }

    private static final Instant AT = Instant.parse("2026-10-16T22:00:00Z");

    static PageSnapshot snapshot(String sourceId, String pageId, String space, String version) {
        return PageSnapshot.of(sourceId, metadata(pageId, space, null, version, "api"),
                new NativePayload(("{\"id\":\"" + pageId + "\",\"v\":" + version + "}")
                        .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"), AT);
    }

    static PageMetadata metadata(String pageId, String space, String parent, String version, String labels) {
        return new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId, "Seite " + pageId, space,
                parent, version, Instant.parse("2026-10-01T08:00:00Z"), null,
                PageMetadata.sorted(Map.of("confluence.labels", labels)));
    }

    static BaselineEntry entry(PageSnapshot snapshot) {
        return new BaselineEntry(snapshot.key().pageId(), snapshot.spaceKey(), snapshot.parentPageId(),
                snapshot.revision(), snapshot.metadataHash(), snapshot.attributesHash());
    }

    static SyncBaseline baseline(String sourceId, String space, long generation, Set<String> stale,
                                 BaselineEntry... entries) {
        Map<String, BaselineEntry> pages = new TreeMap<>();
        for (BaselineEntry entry : entries) {
            pages.put(entry.pageId(), entry);
        }
        return new SyncBaseline(sourceId, space, generation, AT.plusSeconds(generation), new TreeMap<>(pages), new TreeSet<>(stale));
    }

    @Test
    void anEmptyStoreHasNoGeneration() {
        assertThat(empty().store().confirmed("wiki", "DOC")).isEmpty();
    }

    @Test
    void stagedWritesAreInvisibleUntilCommitAndThenVisibleWithTheGeneration() {
        Fixture fixture = empty();
        PageSnapshot page = snapshot("wiki", "1", "DOC", "3");
        SyncBaseline first = baseline("wiki", "DOC", 1, Set.of(), entry(page));

        try (StagedGeneration staged = fixture.store().begin("wiki", "DOC")) {
            staged.save(page);
            assertThat(fixture.snapshots().find(page.key())).isEmpty();
            assertThat(fixture.store().confirmed("wiki", "DOC")).isEmpty();
            staged.commit(first);
        }

        assertThat(fixture.snapshots().find(page.key())).contains(page);
        assertThat(fixture.store().confirmed("wiki", "DOC")).contains(first);
    }

    @Test
    void closingWithoutCommitDiscardsEverything() {
        Fixture fixture = empty();
        PageSnapshot page = snapshot("wiki", "1", "DOC", "3");

        try (StagedGeneration staged = fixture.store().begin("wiki", "DOC")) {
            staged.save(page);
        }

        assertThat(fixture.snapshots().find(page.key())).isEmpty();
        assertThat(fixture.store().confirmed("wiki", "DOC")).isEmpty();
        try (StagedGeneration staged = fixture.store().begin("wiki", "DOC")) {
            staged.commit(baseline("wiki", "DOC", 1, Set.of()));
        }
        assertThat(fixture.store().confirmed("wiki", "DOC").orElseThrow().generation()).isEqualTo(1);
        assertThat(fixture.snapshots().find(page.key())).isEmpty();
    }

    @Test
    void aGenerationThatDoesNotFollowTheConfirmedOneIsAConflictAndLeavesNothing() {
        Fixture fixture = empty();
        PageSnapshot page = snapshot("wiki", "1", "DOC", "3");

        try (StagedGeneration staged = fixture.store().begin("wiki", "DOC")) {
            staged.save(page);
            assertThatThrownBy(() -> staged.commit(baseline("wiki", "DOC", 2, Set.of(), entry(page))))
                    .isInstanceOf(SyncConflictException.class);
        }
        assertThat(fixture.snapshots().find(page.key())).isEmpty();
        assertThat(fixture.store().confirmed("wiki", "DOC")).isEmpty();

        commit(fixture, baseline("wiki", "DOC", 1, Set.of(), entry(page)), page);
        try (StagedGeneration slow = fixture.store().begin("wiki", "DOC")) {
            slow.remove(page.key());
            assertThatThrownBy(() -> slow.commit(baseline("wiki", "DOC", 1, Set.of())))
                    .isInstanceOf(SyncConflictException.class);
        }
        assertThat(fixture.snapshots().find(page.key())).contains(page);
        assertThat(fixture.store().confirmed("wiki", "DOC").orElseThrow().pages()).containsOnlyKeys("1");
    }

    @Test
    void headerUpdatesAndRemovalsBecomeVisibleTogetherWithTheGeneration() {
        Fixture fixture = empty();
        PageSnapshot one = snapshot("wiki", "1", "DOC", "3");
        PageSnapshot two = snapshot("wiki", "2", "DOC", "1");
        commit(fixture, baseline("wiki", "DOC", 1, Set.of(), entry(one), entry(two)), one, two);
        PageHeader relabelled = PageHeader.of("wiki", metadata("1", "DOC", null, "3", "api,veraltet"), AT);

        try (StagedGeneration staged = fixture.store().begin("wiki", "DOC")) {
            staged.saveHeader(relabelled);
            staged.remove(two.key());
            assertThat(fixture.snapshots().findHeader(one.key())).contains(one.header());
            assertThat(fixture.snapshots().find(two.key())).contains(two);
            staged.commit(baseline("wiki", "DOC", 2, Set.of(), new BaselineEntry("1", "DOC", null, "3",
                    relabelled.metadataHash(), relabelled.attributesHash())));
        }

        assertThat(fixture.snapshots().find(one.key())).contains(one);
        assertThat(fixture.snapshots().findHeader(one.key())).contains(relabelled);
        assertThat(fixture.snapshots().find(two.key())).isEmpty();
        assertThat(fixture.snapshots().findHeader(two.key())).isEmpty();
    }

    @Test
    void generationsAreSeparatePerSpaceAndSource() {
        Fixture fixture = empty();
        commit(fixture, baseline("wiki", "DOC", 1, Set.of()));
        commit(fixture, baseline("wiki", "DOC", 2, Set.of()));

        assertThat(fixture.store().confirmed("wiki", "OPS")).isEmpty();
        assertThat(fixture.store().confirmed("other", "DOC")).isEmpty();
        commit(fixture, baseline("wiki", "OPS", 1, Set.of()));
        commit(fixture, baseline("other", "DOC", 1, Set.of()));
        assertThat(fixture.store().confirmed("wiki", "DOC").orElseThrow().generation()).isEqualTo(2);
    }

    @Test
    void theBaselineRoundTripsCompletelyAndSurvivesARestart() {
        Fixture fixture = empty();
        PageSnapshot root = snapshot("wiki", "1", "DOC", "3");
        BaselineEntry child = new BaselineEntry("2", "DOC", "1", null, "sha256:m", "sha256:a");
        SyncBaseline first = baseline("wiki", "DOC", 1, Set.of("2"), entry(root), child);
        commit(fixture, first, root);

        Fixture reopened = reopen(fixture);

        SyncBaseline read = reopened.store().confirmed("wiki", "DOC").orElseThrow();
        assertThat(read).isEqualTo(first);
        assertThat(read.spaceFingerprint()).isEqualTo(first.spaceFingerprint());
        assertThat(reopened.snapshots().find(root.key())).contains(root);
    }

    private static void commit(Fixture fixture, SyncBaseline next, PageSnapshot... snapshots) {
        try (StagedGeneration staged = fixture.store().begin(next.sourceId(), next.spaceKey())) {
            for (PageSnapshot snapshot : snapshots) {
                staged.save(snapshot);
            }
            staged.commit(next);
        }
    }
}
