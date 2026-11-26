package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.baseline;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Änderungsprotokoll an der Schreibstelle des Cache (ADR 0030, CL1). */
class LucenePageCacheChangeLogTest {

    private static final Instant LATER = Instant.parse("2026-10-17T08:00:00Z");

    @TempDir
    Path directory;

    private final List<LucenePageCache> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(LucenePageCache::close);
    }

    private LucenePageCache open() {
        LucenePageCache cache = LucenePageCache.open(directory);
        opened.add(cache);
        return cache;
    }

    private static List<ChangeKind> kinds(LucenePageCache cache) {
        return cache.changeEntries("wiki").stream().map(ChangeEntry::kind).toList();
    }

    @Test
    void aNewPageWithContentIsNewWithItsTitleVersionAndTrigger() {
        LucenePageCache cache = open();
        PageSnapshot page = page("wiki", "101", "DEV", "100", "3", json("{\"v\":3}"));

        ChangeTrigger.MANUAL.run(() -> cache.save(page));

        assertThat(cache.changeEntries("wiki")).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(ChangeKind.NEW);
            assertThat(entry.trigger()).isEqualTo(ChangeTrigger.MANUAL);
            assertThat(entry.spaceKey()).isEqualTo("DEV");
            assertThat(entry.pageId()).isEqualTo("101");
            assertThat(entry.newVersion()).isEqualTo("3");
            assertThat(entry.newTitle()).isEqualTo("Seite 101");
            assertThat(entry.newParentId()).isEqualTo("100");
            assertThat(entry.oldVersion()).isNull();
            assertThat(entry.contentPending()).isFalse();
            assertThat(entry.generation()).isNull();
        });
    }

    @Test
    void withoutATriggerTheRowSaysUnknownNeverBackground() {
        LucenePageCache cache = open();

        cache.save(page("wiki", "101", "DEV", null, "1", json("{}")));

        assertThat(cache.changeEntries("wiki")).extracting(ChangeEntry::trigger)
                .containsExactly(ChangeTrigger.UNKNOWN);
    }

    @Test
    void aCatalogedPageIsNewWithContentPendingAndItsLaterLoadIsLoaded() {
        LucenePageCache cache = open();
        PageSnapshot page = page("wiki", "101", "DEV", null, "1", json("{}"));

        ChangeTrigger.SCHEDULE.run(() -> cache.saveHeader(page.header()));
        ChangeTrigger.SEARCH.run(() -> cache.save(page));

        List<ChangeEntry> entries = cache.changeEntries("wiki");
        assertThat(entries).extracting(ChangeEntry::kind).containsExactly(ChangeKind.NEW, ChangeKind.LOADED);
        assertThat(entries).extracting(ChangeEntry::contentPending).containsExactly(true, false);
        assertThat(entries).extracting(ChangeEntry::trigger)
                .containsExactly(ChangeTrigger.SCHEDULE, ChangeTrigger.SEARCH);
        assertThat(entries.get(1).kind().sourceChange()).isFalse();
    }

    @Test
    void aNewVersionSeenInTheCatalogIsPendingAndLoadingItIsReloadedNotASecondChange() {
        LucenePageCache cache = open();
        cache.save(page("wiki", "101", "DEV", null, "1", json("{\"v\":1}")));
        PageSnapshot second = page("wiki", "101", "DEV", null, "2", json("{\"v\":2}"), LATER);

        cache.saveHeader(second.header());
        cache.save(second);

        List<ChangeEntry> entries = cache.changeEntries("wiki");
        assertThat(entries).extracting(ChangeEntry::kind)
                .containsExactly(ChangeKind.NEW, ChangeKind.CONTENT_CHANGED, ChangeKind.RELOADED);
        ChangeEntry changed = entries.get(1);
        assertThat(changed.contentPending()).isTrue();
        assertThat(changed.oldVersion()).isEqualTo("1");
        assertThat(changed.newVersion()).isEqualTo("2");
    }

    @Test
    void aNewVersionWithItsContentInOneRunIsOneChange() {
        LucenePageCache cache = open();
        cache.save(page("wiki", "101", "DEV", null, "1", json("{\"v\":1}")));
        PageSnapshot second = page("wiki", "101", "DEV", null, "2", json("{\"v\":2}"), LATER);

        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.saveHeader(second.header());
            batch.save(second);
            batch.commit();
        }

        assertThat(cache.changeEntries("wiki")).extracting(ChangeEntry::kind, ChangeEntry::contentPending)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ChangeKind.NEW, false),
                        org.assertj.core.groups.Tuple.tuple(ChangeKind.CONTENT_CHANGED, false));
    }

    @Test
    void anUnversionedPageWhoseCatalogEntryChangedIsAPendingContentChange() {
        LucenePageCache cache = open();
        PageSnapshot page = page("wiki", "101", "DEV", "100", null, json("{}"));
        cache.save(page);
        PageMetadata old = page.metadata();

        cache.saveHeader(PageHeader.of("wiki", new PageMetadata(old.pageId(), old.resourceId(), old.title(),
                old.partitionKey(), old.parentPageId(), null, old.modifiedAt().plusSeconds(60), old.url(),
                old.attributes()), LATER));

        assertThat(cache.changeEntries("wiki")).extracting(ChangeEntry::kind, ChangeEntry::contentPending)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ChangeKind.NEW, false),
                        org.assertj.core.groups.Tuple.tuple(ChangeKind.CONTENT_CHANGED, true));
    }

    @Test
    void renamedMovedMovedOutAndLabelsAreTheirOwnRows() {
        LucenePageCache cache = open();
        PageSnapshot page = page("wiki", "101", "DEV", "100", "1", json("{}"));
        cache.save(page);

        cache.saveHeader(header(page, "Neuer Titel", "DEV", "100", page.metadata().attributes()));
        cache.saveHeader(header(page, "Neuer Titel", "DEV", "200", page.metadata().attributes()));
        cache.saveHeader(header(page, "Neuer Titel", "OPS", "200", page.metadata().attributes()));
        SortedMap<String, String> labels = new TreeMap<>(page.metadata().attributes());
        labels.put("confluence.labels", "api,extern");
        cache.saveHeader(header(page, "Neuer Titel", "OPS", "200", labels));

        List<ChangeEntry> entries = cache.changeEntries("wiki");
        assertThat(entries).extracting(ChangeEntry::kind).containsExactly(ChangeKind.NEW, ChangeKind.RENAMED,
                ChangeKind.MOVED, ChangeKind.MOVED_OUT, ChangeKind.LABELS_CHANGED);
        assertThat(entries.get(1).oldTitle()).isEqualTo("Seite 101");
        assertThat(entries.get(1).newTitle()).isEqualTo("Neuer Titel");
        assertThat(entries.get(2).oldParentId()).isEqualTo("100");
        assertThat(entries.get(2).newParentId()).isEqualTo("200");
        assertThat(entries.get(3).oldSpaceKey()).isEqualTo("DEV");
        assertThat(entries.get(3).newSpaceKey()).isEqualTo("OPS");
        assertThat(entries.get(3).spaceKey()).isEqualTo("OPS");
    }

    @Test
    void removedAndWithheldNameTheFormerSpace() {
        LucenePageCache cache = open();
        PageSnapshot gone = page("wiki", "101", "DEV", null, "1", json("{}"));
        PageSnapshot locked = page("wiki", "102", "DEV", null, "1", json("{}"));
        cache.save(gone);
        cache.save(locked);

        cache.remove(gone.key());
        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.withhold(locked.key());
            batch.commit();
        }

        assertThat(cache.changeEntries("wiki")).extracting(ChangeEntry::kind, ChangeEntry::pageId,
                ChangeEntry::spaceKey).containsSubsequence(
                org.assertj.core.groups.Tuple.tuple(ChangeKind.REMOVED, "101", "DEV"),
                org.assertj.core.groups.Tuple.tuple(ChangeKind.WITHHELD, "102", "DEV"));
    }

    @Test
    void removingAnUnknownPageWritesNothing() {
        LucenePageCache cache = open();

        cache.remove(page("wiki", "101", "DEV", null, "1", json("{}")).key());

        assertThat(cache.changeEntries("wiki")).isEmpty();
    }

    @Test
    void savingAnUnchangedPageAgainWritesNoRowEvenWhenForced() {
        LucenePageCache cache = open();
        PageSnapshot page = page("wiki", "101", "DEV", null, "1", json("{}"));
        cache.save(page);

        cache.save(page("wiki", "101", "DEV", null, "1", json("{}"), LATER));
        cache.saveHeader(page.header());

        assertThat(kinds(cache)).containsExactly(ChangeKind.NEW);
    }

    @Test
    void aDiscardedRunWritesNoRow() {
        LucenePageCache cache = open();

        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.save(page("wiki", "101", "DEV", null, "1", json("{}")));
        }

        assertThat(cache.changeEntries("wiki")).isEmpty();
    }

    @Test
    void rowsOfAConfirmingRunCarryTheGeneration() {
        LucenePageCache cache = open();
        PageSnapshot page = page("wiki", "101", "DEV", null, "1", json("{}"));

        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.save(page);
            batch.confirm(baseline("wiki", "DEV", 1, page));
            batch.commit();
        }

        assertThat(cache.changeEntries("wiki")).extracting(ChangeEntry::generation).containsExactly(1L);
    }

    @Test
    void removingTheSourceRemovesItsLogButNotOtherSources() {
        LucenePageCache cache = open();
        cache.save(page("wiki", "101", "DEV", null, "1", json("{}")));
        cache.save(page("other", "201", "OPS", null, "1", json("{}")));

        cache.removeSource("wiki");

        assertThat(cache.changeEntries("wiki")).isEmpty();
        assertThat(cache.changeEntries("other")).hasSize(1);
    }

    @Test
    void theLogSurvivesARestart() {
        LucenePageCache cache = open();
        ChangeTrigger.READ.run(() -> cache.save(page("wiki", "101", "DEV", null, "1", json("{}"))));
        cache.close();

        assertThat(open().changeEntries("wiki")).extracting(ChangeEntry::trigger)
                .containsExactly(ChangeTrigger.READ);
    }

    @Test
    void pruningRemovesOnlyExpiredRowsAndLeavesPagesAndRevisions() {
        LucenePageCache cache = open();
        PageSnapshot page = page("wiki", "101", "DEV", null, "1", json("{}"));
        cache.save(page);
        Instant written = cache.changeEntries("wiki").get(0).at();

        assertThat(cache.pruneChangeLog(written)).isFalse();
        assertThat(cache.changeEntries("wiki")).hasSize(1);

        assertThat(cache.pruneChangeLog(written.plusNanos(1))).isTrue();
        assertThat(cache.changeEntries("wiki")).isEmpty();
        assertThat(cache.find(page.key())).contains(page);
        assertThat(cache.revisions(page.key())).hasSize(1);
    }

    @Test
    void noPageQueryOrCounterSeesTheLogRows() {
        LucenePageCache cache = open();
        PageSnapshot root = page("wiki", "100", "DEV", null, "1", json("{}"));
        PageSnapshot child = page("wiki", "101", "DEV", "100", "1", json("{}"));
        cache.save(root);
        cache.save(child);
        int documents = cache.documentCount();
        int count = cache.countSpacePages("wiki", "DEV");
        var spacePages = cache.spacePages("wiki", "DEV", null, 100);
        var sourcePages = cache.sourcePages("wiki", null, 100);
        var roots = cache.rootPages("wiki", "DEV", null, 100);
        var children = cache.children(root.key(), null, 100);
        var known = cache.knownPages("wiki", "DEV");
        assertThat(cache.changeEntries("wiki")).isNotEmpty();

        cache.pruneChangeLog(Instant.now().plusSeconds(60));

        assertThat(cache.changeEntries("wiki")).isEmpty();
        assertThat(cache.documentCount()).isEqualTo(documents);
        assertThat(cache.countSpacePages("wiki", "DEV")).isEqualTo(count).isEqualTo(2);
        assertThat(cache.spacePages("wiki", "DEV", null, 100)).isEqualTo(spacePages);
        assertThat(cache.sourcePages("wiki", null, 100)).isEqualTo(sourcePages);
        assertThat(cache.rootPages("wiki", "DEV", null, 100)).isEqualTo(roots);
        assertThat(cache.children(root.key(), null, 100)).isEqualTo(children);
        assertThat(cache.knownPages("wiki", "DEV")).isEqualTo(known);
    }

    @Test
    void updateMarksSurviveARestartAndStayOutOfCounters() {
        LucenePageCache cache = open();
        cache.save(page("wiki", "101", "DEV", null, "1", json("{}")));
        int documents = cache.documentCount();
        UpdateMark first = new UpdateMark("wiki", LATER, "client:a", List.of("DEV", "OPS"), 1, true, List.of("OPS"));

        cache.mark(first);
        cache.mark(new UpdateMark("other", LATER, null, List.of(), 0, true));
        cache.close();
        LucenePageCache reopened = open();

        assertThat(reopened.marks("wiki")).containsExactly(first);
        assertThat(reopened.documentCount()).isEqualTo(documents);
        assertThat(reopened.countSpacePages("wiki", "DEV")).isEqualTo(1);
    }

    @Test
    void pruningKeepsTheNewestMarkOfEachSourceAndRemovingTheSourceRemovesItsMarks() {
        LucenePageCache cache = open();
        UpdateMark old = new UpdateMark("wiki", LATER, null, List.of("DEV"), 0, true);
        UpdateMark newer = new UpdateMark("wiki", LATER.plusSeconds(60), null, List.of("DEV"), 0, false);
        UpdateMark other = new UpdateMark("other", LATER, null, List.of("OPS"), 0, true);
        cache.mark(old);
        cache.mark(newer);
        cache.mark(other);

        assertThat(cache.pruneChangeLog(LATER.plusSeconds(3600))).isTrue();

        assertThat(cache.marks("wiki")).containsExactly(newer);
        assertThat(cache.marks("other")).containsExactly(other);
        assertThat(cache.pruneChangeLog(LATER.plusSeconds(3600))).isFalse();

        cache.removeSource("wiki");
        assertThat(cache.marks("wiki")).isEmpty();
        assertThat(cache.marks("other")).containsExactly(other);
    }

    private static PageHeader header(PageSnapshot page, String title, String spaceKey, String parentId,
                                     Map<String, String> attributes) {
        PageMetadata old = page.metadata();
        return PageHeader.of(page.key().sourceId(), new PageMetadata(old.pageId(), old.resourceId(), title, spaceKey,
                parentId, old.version(), old.modifiedAt(), old.url(), PageMetadata.sorted(attributes)), LATER);
    }
}
