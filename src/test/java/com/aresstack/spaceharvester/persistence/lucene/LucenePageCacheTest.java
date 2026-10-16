package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.baseline;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import com.aresstack.spaceharvester.application.sync.SyncConflictException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KeywordField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persistenz, Abfragen, Schreibläufe und Fehlerfälle des Lucene-Caches auf temporären Verzeichnissen. */
class LucenePageCacheTest {

    /** Bytes, die kein gültiges UTF-8 sind. */
    private static final byte[] NOT_UTF8 = {'{', 0x00, (byte) 0xC3, (byte) 0x28, (byte) 0xFF, '}'};

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

    private LucenePageCache reopen(LucenePageCache cache) {
        cache.close();
        return open();
    }

    // --- Neustart -------------------------------------------------------------------------------------------------

    @Test
    void pagesRevisionsBytesAndParentsSurviveARestart() {
        LucenePageCache cache = open();
        PageSnapshot parent = page("wiki", "100", "DEV", null, "1", json("{\"id\":\"100\"}"));
        PageSnapshot child = page("wiki", "101", "DEV", "100", "3", NOT_UTF8);
        cache.save(parent);
        cache.save(child);

        LucenePageCache restarted = reopen(cache);

        assertThat(restarted.find(child.key())).contains(child);
        assertThat(restarted.find(child.key()).orElseThrow().payload().bytes()).isEqualTo(NOT_UTF8);
        assertThat(restarted.revisions(child.key())).extracting(CachedRevision::revision).containsExactly("3");
        assertThat(restarted.children(parent.key(), 10)).extracting(CachedPageHeader::key)
                .containsExactly(child.key());
        assertThat(restarted.rootPages("wiki", "DEV", 10)).extracting(CachedPageHeader::key)
                .containsExactly(parent.key());
    }

    // --- Upsert und Verlauf ---------------------------------------------------------------------------------------

    @Test
    void savingTheSameRevisionAgainCreatesNoDuplicates() {
        LucenePageCache cache = open();
        PageSnapshot snapshot = page("wiki", "101", "DEV", "100", "3", json("{\"v\":3}"));

        cache.save(snapshot);
        int documents = cache.documentCount();
        cache.save(snapshot);
        cache.save(page("wiki", "101", "DEV", "100", "3", json("{\"v\":3,\"_links\":{}}"),
                Instant.parse("2026-10-16T21:00:00Z")));

        assertThat(cache.documentCount()).isEqualTo(documents);
        assertThat(cache.revisions(snapshot.key())).singleElement()
                .satisfies(revision -> assertThat(revision.retrievedAt())
                        .isEqualTo(Instant.parse("2026-10-16T21:00:00Z")));
        assertThat(cache.spacePages("wiki", "DEV", null, 10)).hasSize(1);
    }

    @Test
    void aNewRevisionIsTraceableInTheHistory() {
        LucenePageCache cache = open();
        PageSnapshot v3 = page("wiki", "101", "DEV", "100", "3", json("{\"v\":3}"));
        PageSnapshot v4 = page("wiki", "101", "DEV", "100", "4", json("{\"v\":4}"),
                Instant.parse("2026-10-16T21:00:00Z"));

        cache.save(v3);
        cache.save(v4);

        assertThat(cache.find(v3.key())).contains(v4);
        assertThat(cache.revisions(v3.key()))
                .extracting(CachedRevision::revision, CachedRevision::rawSha256)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("3", v3.payload().rawSha256()),
                        org.assertj.core.groups.Tuple.tuple("4", v4.payload().rawSha256()));
    }

    @Test
    void parentAndSpaceChangeIndependentlyOfThePayload() {
        LucenePageCache cache = open();
        byte[] body = json("{\"id\":\"101\"}");
        PageSnapshot before = page("wiki", "101", "DEV", "100", "3", body);
        PageSnapshot moved = page("wiki", "101", "OPS", "200", "3", body);

        cache.save(before);
        cache.save(moved);

        assertThat(cache.findHeader(before.key()).orElseThrow().metadata().parentPageId()).isEqualTo("200");
        assertThat(cache.spacePages("wiki", "DEV", null, 10)).isEmpty();
        assertThat(cache.spacePages("wiki", "OPS", null, 10)).singleElement()
                .satisfies(listed -> assertThat(listed.rawSha256()).isEqualTo(before.payload().rawSha256()));
        assertThat(cache.children(new PageKey("wiki", "100"), 10)).isEmpty();
        assertThat(cache.children(new PageKey("wiki", "200"), 10)).hasSize(1);
    }

    @Test
    void aHeaderUpdateMovesThePageInListingsButKeepsThePayload() {
        LucenePageCache cache = open();
        PageSnapshot snapshot = page("wiki", "101", "DEV", "100", "3", NOT_UTF8);
        cache.save(snapshot);

        cache.saveHeader(moved(snapshot, "OPS", "200"));

        assertThat(cache.spacePages("wiki", "DEV", null, 10)).isEmpty();
        assertThat(cache.spacePages("wiki", "OPS", null, 10)).singleElement().satisfies(listed -> {
            assertThat(listed.header().metadata().parentPageId()).isEqualTo("200");
            assertThat(listed.rawSha256()).isEqualTo(snapshot.payload().rawSha256());
        });
        assertThat(cache.children(new PageKey("wiki", "200"), 10)).hasSize(1);
        assertThat(cache.find(snapshot.key())).contains(snapshot);
        assertThat(cache.revisions(snapshot.key())).hasSize(1);
    }

    @Test
    void aPageKnownOnlyByItsHeaderIsListedWithoutPayloadHash() {
        LucenePageCache cache = open();
        PageHeader header = page("wiki", "105", "DEV", null, "1", json("{}")).header();

        cache.saveHeader(header);

        assertThat(cache.spacePages("wiki", "DEV", null, 10)).singleElement()
                .satisfies(listed -> assertThat(listed.rawSha256()).isNull());
        assertThat(cache.rootPages("wiki", "DEV", 10)).hasSize(1);
        assertThat(cache.find(header.key())).isEmpty();
        assertThat(cache.revisions(header.key())).isEmpty();
    }

    @Test
    void aHeaderUpdateInTheSameRunKeepsThePayloadSavedEarlierInThatRun() {
        LucenePageCache cache = open();
        PageSnapshot snapshot = page("wiki", "101", "DEV", "100", "3", NOT_UTF8);
        PageHeader moved = moved(snapshot, "OPS", null);

        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.save(snapshot);
            batch.saveHeader(moved);
            batch.commit();
        }

        assertThat(cache.find(snapshot.key())).contains(snapshot);
        assertThat(cache.findHeader(snapshot.key())).contains(moved);
    }

    @Test
    void removeDropsThePageAndItsHistory() {
        LucenePageCache cache = open();
        PageSnapshot snapshot = page("wiki", "101", "DEV", "100", "3", json("{}"));
        cache.save(snapshot);
        cache.save(page("wiki", "101", "DEV", "100", "4", json("{}")));

        assertThat(cache.remove(snapshot.key())).isTrue();

        assertThat(cache.revisions(snapshot.key())).isEmpty();
        assertThat(cache.documentCount()).isZero();
    }

    // --- Abfragen ohne Payload ------------------------------------------------------------------------------------

    @Test
    void spacePagesArePagedByPageIdAndSeparatedBySource() {
        LucenePageCache cache = open();
        for (String id : List.of("105", "101", "103", "102", "104")) {
            cache.save(page("wiki", id, "DEV", null, "1", json("{}")));
        }
        cache.save(page("archive", "101", "DEV", null, "1", json("{}")));

        List<CachedPageHeader> first = cache.spacePages("wiki", "DEV", null, 2);
        List<CachedPageHeader> second = cache.spacePages("wiki", "DEV", "102", 2);
        List<CachedPageHeader> last = cache.spacePages("wiki", "DEV", "104", 2);

        assertThat(first).extracting(h -> h.key().pageId()).containsExactly("101", "102");
        assertThat(second).extracting(h -> h.key().pageId()).containsExactly("103", "104");
        assertThat(last).extracting(h -> h.key().pageId()).containsExactly("105");
        assertThat(cache.countSpacePages("wiki", "DEV")).isEqualTo(5);
        assertThat(cache.countSpacePages("archive", "DEV")).isEqualTo(1);
    }

    @Test
    void headersCarryTheMetadataAndHashesWithoutThePayload() {
        LucenePageCache cache = open();
        PageSnapshot snapshot = page("wiki", "101", "DEV", "100", "3", NOT_UTF8);
        cache.save(snapshot);

        CachedPageHeader listed = cache.spacePages("wiki", "DEV", null, 10).get(0);

        assertThat(listed.header()).isEqualTo(snapshot.header());
        assertThat(listed.rawSha256()).isEqualTo(snapshot.payload().rawSha256());
    }

    @Test
    void limitIsBounded() {
        LucenePageCache cache = open();
        assertThatThrownBy(() -> cache.spacePages("wiki", "DEV", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.spacePages("wiki", "DEV", null, LucenePageCache.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Schreibläufe und Generationen ------------------------------------------------------------------------------

    @Test
    void aBatchBecomesVisibleOnlyWithItsCommit() {
        LucenePageCache cache = open();
        PageSnapshot snapshot = page("wiki", "101", "DEV", null, "1", json("{}"));

        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.save(snapshot);
            batch.confirm(baseline("wiki", "DEV", 1, snapshot));

            assertThat(cache.find(snapshot.key())).isEmpty();
            assertThat(cache.confirmedGeneration("wiki", "DEV")).isEmpty();

            batch.commit();
        }

        assertThat(cache.find(snapshot.key())).contains(snapshot);
        assertThat(cache.confirmedGeneration("wiki", "DEV")).contains(
                new ConfirmedGeneration("wiki", "DEV", 1, TestSnapshots.CONFIRMED, 0));
        assertThat(cache.confirmed("wiki", "DEV")).contains(baseline("wiki", "DEV", 1, snapshot));
    }

    @Test
    void anAbortedRunLeavesTheConfirmedStateUntouched() {
        LucenePageCache cache = open();
        PageSnapshot confirmed = page("wiki", "101", "DEV", null, "1", json("{\"v\":1}"));
        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.save(confirmed);
            batch.confirm(baseline("wiki", "DEV", 1, confirmed));
            batch.commit();
        }

        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.save(page("wiki", "101", "DEV", null, "2", json("{\"v\":2}")));
            batch.remove(new PageKey("wiki", "101"));
            batch.confirm(baseline("wiki", "DEV", 2));
            // kein Commit: Abbruch
        }

        assertThat(cache.find(confirmed.key())).contains(confirmed);
        assertThat(cache.confirmed("wiki", "DEV")).contains(baseline("wiki", "DEV", 1, confirmed));
        // Der Writer arbeitet nach dem Abbruch weiter.
        PageSnapshot later = page("wiki", "102", "DEV", null, "1", json("{}"));
        cache.save(later);
        assertThat(reopen(cache).find(later.key())).contains(later);
    }

    @Test
    void aCrashBeforeTheCommitIsRecoveredToTheLastCommit() {
        LucenePageCache cache = open();
        PageSnapshot confirmed = page("wiki", "101", "DEV", null, "1", json("{\"v\":1}"));
        cache.save(confirmed);

        LucenePageCache.Batch batch = cache.begin();
        PageSnapshot newer = page("wiki", "101", "DEV", null, "2", json("{\"v\":2}"));
        PageSnapshot added = page("wiki", "102", "DEV", null, "1", json("{}"));
        batch.save(newer);
        batch.save(added);
        batch.confirm(baseline("wiki", "DEV", 1, newer, added));
        // Dienst endet, ohne den Lauf zu bestätigen.
        cache.close();
        batch.close();

        LucenePageCache recovered = open();
        assertThat(recovered.find(confirmed.key())).contains(confirmed);
        assertThat(recovered.find(added.key())).isEmpty();
        assertThat(recovered.confirmed("wiki", "DEV")).isEmpty();
    }

    @Test
    void aNewGenerationReplacesAllPageEntriesOfThePreviousOne() {
        LucenePageCache cache = open();
        PageSnapshot one = page("wiki", "101", "DEV", null, "1", json("{}"));
        PageSnapshot two = page("wiki", "102", "DEV", "101", "1", json("{}"));
        confirm(cache, baseline("wiki", "DEV", 1, one, two));
        int documents = cache.documentCount();

        confirm(cache, baseline("wiki", "DEV", 2, one));

        assertThat(reopen(cache).confirmed("wiki", "DEV").orElseThrow().pages()).containsOnlyKeys("101");
        assertThat(opened.get(opened.size() - 1).documentCount()).isEqualTo(documents - 1);
    }

    @Test
    void aGenerationMustFollowTheConfirmedOne() {
        LucenePageCache cache = open();
        confirm(cache, baseline("wiki", "DEV", 1));

        try (LucenePageCache.Batch batch = cache.begin()) {
            assertThatThrownBy(() -> batch.confirm(baseline("wiki", "DEV", 1)))
                    .isInstanceOf(SyncConflictException.class);
            assertThatThrownBy(() -> batch.confirm(baseline("wiki", "DEV", 3)))
                    .isInstanceOf(SyncConflictException.class);
        }
        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.confirm(baseline("wiki", "DEV", 2));
            assertThatThrownBy(() -> batch.confirm(baseline("wiki", "DEV", 3)))
                    .isInstanceOf(SyncConflictException.class);
        }

        assertThat(cache.confirmedGeneration("wiki", "DEV").orElseThrow().generation()).isEqualTo(1);
    }

    @Test
    void stalePagesAreCountedInTheGenerationHead() {
        LucenePageCache cache = open();
        PageSnapshot one = page("wiki", "101", "DEV", null, "1", json("{}"));
        PageSnapshot two = page("wiki", "102", "DEV", null, "1", json("{}"));
        SyncBaseline withStale = new SyncBaseline("wiki", "DEV", 1, TestSnapshots.CONFIRMED,
                baseline("wiki", "DEV", 1, one, two).pages(), new TreeSet<>(Set.of("102")));
        confirm(cache, withStale);

        assertThat(cache.confirmedGeneration("wiki", "DEV").orElseThrow().stalePageCount()).isEqualTo(1);
        assertThat(cache.confirmed("wiki", "DEV")).contains(withStale);
    }

    @Test
    void aFinishedBatchCannotBeUsedAgain() {
        LucenePageCache cache = open();
        LucenePageCache.Batch batch = cache.begin();
        batch.commit();

        assertThatThrownBy(() -> batch.save(page("wiki", "101", "DEV", null, "1", json("{}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Grenzen und Fehlerfälle ----------------------------------------------------------------------------------

    @Test
    void anOversizedPayloadIsRejectedAndNotTruncated() {
        LucenePageCache cache = LucenePageCache.open(directory, 8);
        opened.add(cache);
        PageSnapshot large = page("wiki", "101", "DEV", null, "1", json("{\"too\":\"large\"}"));

        assertThatThrownBy(() -> cache.save(large)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht gekürzt");
        assertThat(cache.find(large.key())).isEmpty();
    }

    @Test
    void aSecondProcessCannotOpenTheSameIndex() {
        open();

        assertThatThrownBy(() -> LucenePageCache.open(directory)).isInstanceOf(PageCacheException.class)
                .hasMessageContaining("anderen Prozess");
    }

    @Test
    void anIndexWithAnotherSchemaIsNotOpened() throws IOException {
        try (FSDirectory dir = FSDirectory.open(directory);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, "page-cache/v0").entrySet());
            writer.commit();
        }

        assertThatThrownBy(() -> LucenePageCache.open(directory)).isInstanceOf(PageCacheException.class)
                .hasMessageContaining("page-cache/v0").hasMessageContaining(PageCacheSchema.VERSION);
        // Die Sperre ist wieder frei: nach dem Löschen lässt sich neu aufbauen.
        deleteContents(directory);
        assertThat(open().documentCount()).isZero();
    }

    @Test
    void aCorruptIndexIsReportedNotSilentlyReplaced() throws IOException {
        LucenePageCache cache = open();
        cache.save(page("wiki", "101", "DEV", null, "1", json("{}")));
        cache.close();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("segments_")).toList()) {
                Files.write(file, new byte[] {1, 2, 3, 4});
            }
        }

        assertThatThrownBy(() -> LucenePageCache.open(directory)).isInstanceOf(PageCacheException.class)
                .hasMessageContaining("neu ernten");
    }

    @Test
    void aPayloadThatNoLongerMatchesItsHashIsAnError() throws IOException {
        PageSnapshot snapshot = page("wiki", "101", "DEV", null, "1", json("{\"v\":1}"));
        open().close();
        try (FSDirectory dir = FSDirectory.open(directory);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document forged = PageCacheSchema.page(snapshot.header(), snapshot);
            forged.removeField(PageCacheSchema.RAW_SHA256);
            forged.add(new KeywordField(PageCacheSchema.RAW_SHA256, "sha256:00", Field.Store.YES));
            writer.addDocument(forged);
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, PageCacheSchema.VERSION).entrySet());
            writer.commit();
        }

        LucenePageCache cache = open();

        assertThatThrownBy(() -> cache.find(snapshot.key())).isInstanceOf(PageCacheException.class)
                .hasMessageContaining("beschädigt").hasMessageNotContaining("\"v\"");
    }

    @Test
    void aClosedCacheRefusesAccess() {
        LucenePageCache cache = open();
        cache.close();

        assertThatThrownBy(() -> cache.find(new PageKey("wiki", "101"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(cache::begin).isInstanceOf(IllegalStateException.class);
    }

    private static void confirm(LucenePageCache cache, SyncBaseline next) {
        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.confirm(next);
            batch.commit();
        }
    }

    private static PageHeader moved(PageSnapshot snapshot, String spaceKey, String parentId) {
        PageMetadata old = snapshot.metadata();
        return PageHeader.of(snapshot.key().sourceId(), new PageMetadata(old.pageId(), old.resourceId(), old.title(),
                spaceKey, parentId, old.version(), old.modifiedAt(), old.url(), old.attributes()),
                Instant.parse("2026-10-16T21:00:00Z"));
    }

    private static void deleteContents(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }
}
