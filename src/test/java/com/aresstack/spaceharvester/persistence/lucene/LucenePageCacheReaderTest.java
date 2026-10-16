package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.StagedGeneration;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der lesende Port der Query-API über dem Lucene-Cache: Generationen, Seitenliste und Rohseite. */
class LucenePageCacheReaderTest {

    private static final Instant FIRST_RUN = Instant.parse("2026-10-16T20:00:00Z");
    private static final Instant SECOND_RUN = Instant.parse("2026-10-16T21:00:00Z");

    @TempDir
    Path directory;

    private LucenePageCache cache;
    private LucenePageCacheReader reader;

    @BeforeEach
    void open() {
        cache = LucenePageCache.open(directory);
        reader = new LucenePageCacheReader(cache);
    }

    @AfterEach
    void close() {
        cache.close();
    }

    @Test
    void anUnknownSpaceHasNoGeneration() {
        assertThat(reader.currentGeneration("wiki", "DEV")).isEmpty();
    }

    @Test
    void pagesWithoutAnyRecordedRunAreAnIncompleteInitialGeneration() {
        cache.save(page("wiki", "101", "DEV", null, "1", json("{}")));

        assertThat(reader.currentGeneration("wiki", "DEV"))
                .contains(new CacheGeneration("g0", CacheState.INCOMPLETE, null, null));
    }

    @Test
    void aConfirmedGenerationIsConfirmed() {
        run(1, FIRST_RUN, Set.of(), page("wiki", "101", "DEV", null, "1", json("{}")));

        assertThat(reader.currentGeneration("wiki", "DEV"))
                .contains(new CacheGeneration("g1", CacheState.CONFIRMED, FIRST_RUN, FIRST_RUN));
    }

    @Test
    void aGenerationWithStalePagesIsStale() {
        run(1, FIRST_RUN, Set.of(), page("wiki", "101", "DEV", null, "1", json("{}")));
        run(2, SECOND_RUN, Set.of("101"), page("wiki", "101", "DEV", null, "1", json("{}")));

        assertThat(reader.currentGeneration("wiki", "DEV"))
                .contains(new CacheGeneration("g2", CacheState.STALE, SECOND_RUN, SECOND_RUN));
    }

    @Test
    void aGenerationWithMissingPagesIsIncompleteEvenWithStalePages() {
        run(1, FIRST_RUN, Set.of(), page("wiki", "101", "DEV", null, "1", json("{}")));
        SortedMap<String, BaselineEntry> entries = new LuceneSyncBaselineStore(cache).confirmed("wiki", "DEV")
                .orElseThrow().pages();
        try (StagedGeneration staged = new LuceneSyncBaselineStore(cache).begin("wiki", "DEV")) {
            staged.commit(new SyncBaseline("wiki", "DEV", 2, SECOND_RUN, entries, new TreeSet<>(Set.of("101")),
                    new TreeSet<>(Set.of("102"))));
        }

        assertThat(reader.currentGeneration("wiki", "DEV"))
                .contains(new CacheGeneration("g2", CacheState.INCOMPLETE, SECOND_RUN, SECOND_RUN));
    }

    @Test
    void anUncommittedRunDoesNotChangeTheGeneration() {
        run(1, FIRST_RUN, Set.of(), page("wiki", "101", "DEV", null, "1", json("{}")));

        try (StagedGeneration staged = new LuceneSyncBaselineStore(cache).begin("wiki", "DEV")) {
            staged.save(page("wiki", "102", "DEV", null, "1", json("{}")));
            assertThat(reader.currentGeneration("wiki", "DEV").orElseThrow().id()).isEqualTo("g1");
            assertThat(reader.pages("wiki", "DEV", "g1", null, 10)).extracting(ListedPage::pageId)
                    .containsExactly("101");
        }
    }

    @Test
    void pagesAreListedByPageIdWithTheirPayloadHash() {
        PageSnapshot root = page("wiki", "101", "DEV", null, "1", json("{\"id\":\"101\"}"));
        PageSnapshot child = page("wiki", "102", "DEV", "101", "1", json("{\"id\":\"102\"}"));
        run(1, FIRST_RUN, Set.of(), child, root, page("wiki", "201", "OPS", null, "1", json("{}")));
        cache.saveHeader(page("wiki", "103", "DEV", "101", "1", json("{}")).header());

        assertThat(reader.pages("wiki", "DEV", "g1", null, 10)).extracting(ListedPage::pageId, ListedPage::rawSha256)
                .containsExactly(
                        tuple("101", root.payload().rawSha256()),
                        tuple("102", child.payload().rawSha256()),
                        tuple("103", null));
        assertThat(reader.pages("wiki", "DEV", "g1", "101", 1)).extracting(ListedPage::pageId)
                .containsExactly("102");
    }

    @Test
    void aListingForAnOlderGenerationIsRefused() {
        run(1, FIRST_RUN, Set.of(), page("wiki", "101", "DEV", null, "1", json("{}")));
        run(2, SECOND_RUN, Set.of(), page("wiki", "101", "DEV", null, "2", json("{}")));

        assertThatThrownBy(() -> reader.pages("wiki", "DEV", "g1", null, 10))
                .isInstanceOf(CacheGenerationChangedException.class);
        assertThatThrownBy(() -> reader.pages("wiki", "OPS", "g0", null, 10))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void aStoredPageComesWithItsBytesAndTheGenerationOfItsSpace() {
        PageSnapshot snapshot = page("wiki", "101", "DEV", null, "1", new byte[] {'{', 0x00, (byte) 0xFF, '}'});
        run(1, FIRST_RUN, Set.of(), snapshot);

        assertThat(reader.page(snapshot.key())).hasValueSatisfying(page -> {
            assertThat(page.snapshot()).isEqualTo(snapshot);
            assertThat(page.snapshot().payload().bytes()).isEqualTo(snapshot.payload().bytes());
            assertThat(page.header()).isEqualTo(snapshot.header());
            assertThat(page.generation().id()).isEqualTo("g1");
            assertThat(page.generation().state()).isEqualTo(CacheState.CONFIRMED);
        });
    }

    @Test
    void aPageKnownOnlyByItsHeaderHasNoRawPage() {
        PageSnapshot snapshot = page("wiki", "101", "DEV", null, "1", json("{}"));
        cache.saveHeader(snapshot.header());

        assertThat(reader.page(snapshot.key())).isEmpty();
        assertThat(reader.page(new PageKey("wiki", "999"))).isEmpty();
    }

    @Test
    void aClosedCacheIsUnavailable() {
        cache.close();

        assertThatThrownBy(() -> reader.currentGeneration("wiki", "DEV"))
                .isInstanceOf(CacheUnavailableException.class);
        assertThatThrownBy(() -> reader.page(new PageKey("wiki", "101")))
                .isInstanceOf(CacheUnavailableException.class);
    }

    /** Ein bestätigter Lauf über {@code wiki/DEV}; Seiten anderer Spaces werden nur mitgespeichert. */
    private void run(long generation, Instant confirmedAt, Set<String> stale, PageSnapshot... snapshots) {
        SortedMap<String, BaselineEntry> entries = new TreeMap<>();
        try (StagedGeneration staged = new LuceneSyncBaselineStore(cache).begin("wiki", "DEV")) {
            for (PageSnapshot snapshot : snapshots) {
                staged.save(snapshot);
                if (snapshot.spaceKey().equals("DEV")) {
                    entries.put(snapshot.key().pageId(), new BaselineEntry(snapshot.key().pageId(), "DEV",
                            snapshot.parentPageId(), snapshot.revision(), snapshot.metadataHash(),
                            snapshot.attributesHash()));
                }
            }
            staged.commit(new SyncBaseline("wiki", "DEV", generation, confirmedAt, entries, new TreeSet<>(stale)));
        }
    }
}
