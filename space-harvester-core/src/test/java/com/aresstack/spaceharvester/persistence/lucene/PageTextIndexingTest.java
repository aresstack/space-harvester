package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageTextIndexingTest {

    @TempDir
    Path dir;

    @Test
    void aRenameOrMoveWithoutNewVersionReachesTheIndexFromTheCurrentHeader() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            PageTextIndexing texts = new PageTextIndexing(List.of(new ConfluenceStorageTextProjector()), index);
            PageSnapshot snapshot = TestSnapshots.page("wiki", "7", "DEV", null, "3", TestSnapshots.json(
                    "{\"body\":{\"storage\":{\"value\":\"<p>Nachtlauf</p>\"}}}"));
            PageMetadata old = snapshot.metadata();
            PageMetadata moved = new PageMetadata(old.pageId(), old.resourceId(), "Betriebshandbuch", "OPS",
                    old.parentPageId(), old.version(), old.modifiedAt(), old.url(), old.attributes());

            assertThat(texts.index(snapshot)).isTrue();
            assertThat(texts.index(snapshot)).isFalse();
            // Gleicher Text, aber neuer Titel und Space: neu indiziert.
            assertThat(texts.index(snapshot, moved)).isTrue();

            List<PageTextIndex.Match> hits = index.search("betriebshandbuch", Set.of("wiki"), SearchSpaces.all(), 10);
            assertThat(hits).extracting(PageTextIndex.Match::spaceKey).containsExactly("OPS");
        }
    }

    @Test
    void aCachedPageMissingFromTheIndexIsIndexedBeforeItsVersionCounts() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            PageTextIndexing texts = new PageTextIndexing(List.of(new ConfluenceStorageTextProjector()), index);
            PageSnapshot snapshot = TestSnapshots.page("wiki", "8", "DEV", null, "4", TestSnapshots.json(
                    "{\"body\":{\"storage\":{\"value\":\"<p>Nachtlauf</p>\"}}}"));
            PageHeader header = PageHeader.of("wiki", snapshot.metadata(), Instant.EPOCH);

            // Im Cache, aber nicht im Index (etwa nach einem neu aufgebauten Index): erst nachindizieren.
            assertThat(texts.cachedVersion(snapshot.key(), key -> Optional.of(header), key -> Optional.of(snapshot)))
                    .contains("4");
            assertThat(index.search("nachtlauf", Set.of("wiki"), SearchSpaces.all(), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactly("8");
            assertThat(texts.cachedVersion(snapshot.key(), key -> Optional.empty(), key -> Optional.of(snapshot)))
                    .isEmpty();
            // Neuere Kopfdaten aus einem Katalogabgleich: die Payload ist noch Version 4 und muss geladen werden.
            PageHeader newer = PageHeader.of("wiki", TestSnapshots.page("wiki", "8", "DEV", null, "5",
                    TestSnapshots.json("{}")).metadata(), Instant.EPOCH);
            assertThat(texts.cachedVersion(snapshot.key(), key -> Optional.of(newer), key -> Optional.of(snapshot)))
                    .contains("4");
        }
    }
}
