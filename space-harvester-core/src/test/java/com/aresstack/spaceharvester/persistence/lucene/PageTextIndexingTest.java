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

    /** Einträge aus der Zeit vor dem Artenfilter tragen keinen Medientyp; das nächste Indizieren schreibt ihn nach. */
    @Test
    void anEntryWithoutMediaTypeIsRewrittenOnceWithTheMediaTypeOfThePayload() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            PageTextIndexing texts = new PageTextIndexing(List.of(new ConfluenceStorageTextProjector()), index);
            PageSnapshot snapshot = TestSnapshots.page("wiki", "9", "DEV", null, "1", TestSnapshots.json(
                    "{\"body\":{\"storage\":{\"value\":\"<p>Nachtlauf</p>\"}}}"));
            assertThat(texts.index(snapshot)).isTrue();
            String state = index.inputSha256(snapshot.key()).orElseThrow();
            // Wie ein alter Eintrag: gleicher Stand, ohne Medientyp.
            index.put(new PageTextIndex.Entry(snapshot.key(), "DEV", snapshot.metadata().title(), "Nachtlauf", state));
            assertThat(index.mediaType(snapshot.key())).isEmpty();

            assertThat(texts.index(snapshot)).as("einmal nachgeschrieben").isTrue();
            assertThat(index.mediaType(snapshot.key())).contains(snapshot.payload().mediaType());
            assertThat(texts.index(snapshot)).as("danach unverändert").isFalse();
        }
    }

    /** Eine Confluence-Überschrift, die genau der Anfrage entspricht, stellt ihre Seite nach oben. */
    @Test
    void aConfluenceHeadingMatchingTheQueryPutsItsPageOnTop() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            PageTextIndexing texts = new PageTextIndexing(List.of(new ConfluenceStorageTextProjector()), index);
            String filler = "<p>" + "Füllwort ".repeat(60) + "</p>";
            assertThat(texts.index(TestSnapshots.page("wiki", "1", "DEV", null, "1", TestSnapshots.json(
                    "{\"body\":{\"storage\":{\"value\":\"<p>Störungsmeldung hier, Störungsmeldung dort.</p>\"}}}"))))
                    .isTrue();
            assertThat(texts.index(TestSnapshots.page("wiki", "2", "DEV", null, "1", TestSnapshots.json(
                    "{\"body\":{\"storage\":{\"value\":\"" + filler + "<h2>Störungsmeldung</h2><p>Erst anrufen.</p>"
                            + filler + "\"}}}")))).isTrue();

            List<PageTextIndex.Match> hits = index.search("störungsmeldung", Set.of("wiki"), SearchSpaces.all(), 10);

            assertThat(hits).extracting(PageTextIndex.Match::pageId).containsExactly("2", "1");
            assertThat(hits.get(0).snippet()).contains("Störungsmeldung Erst anrufen.");
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
