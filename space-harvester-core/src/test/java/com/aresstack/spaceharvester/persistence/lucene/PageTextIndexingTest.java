package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.nio.file.Path;
import java.util.List;
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
}
