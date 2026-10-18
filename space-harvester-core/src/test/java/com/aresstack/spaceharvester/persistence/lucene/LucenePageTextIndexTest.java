package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LucenePageTextIndexTest {

    @TempDir
    Path dir;

    @Test
    void ranksByBm25WithTheTitleWeightedAndAWindowAroundTheFirstFinding() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "Betrieb", "Einleitung. " + "Füllwort ".repeat(80)
                    + "Der Nachtlauf startet um zwei Uhr. " + "Füllwort ".repeat(80)));
            index.put(entry("wiki", "2", "DEV", "Nachtlauf", "Steht im Titel und im Text: Nachtlauf."));
            index.put(entry("wiki", "3", "OPS", "Urlaub", "Nichts dazu."));

            var matches = index.search("NACHTLAUF", Set.of("wiki"), SearchSpaces.all(), 10);

            assertThat(matches).extracting(PageTextIndex.Match::pageId).containsExactly("2", "1");
            String window = matches.get(1).snippet();
            assertThat(window).contains("Der Nachtlauf startet um zwei Uhr.").startsWith("… ").endsWith(" …");
            assertThat(window.length()).isLessThanOrEqualTo(LucenePageTextIndex.WINDOW + LucenePageTextIndex.WINDOW);
            assertThat(window).doesNotContain("  ");
        }
    }

    @Test
    void theWindowIsAroundAWholeWordNotAPartOfOne() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "Die party beginnt. " + "Füllwort ".repeat(80)
                    + "Moderne art im Foyer."));

            String window = index.search("art", Set.of("wiki"), SearchSpaces.all(), 10).getFirst().snippet();

            assertThat(window).contains("Moderne art im Foyer.").doesNotContain("party");
        }
    }

    @Test
    void filtersBySourceAndSpacesAndNeedsAtLeastOneWord() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "Nachtlauf Prüfung"));
            index.put(entry("wiki", "2", "OPS", "B", "Nachtlauf"));
            index.put(entry("docs", "3", "docs", "C", "Nachtlauf"));

            assertThat(index.search("nachtlauf", Set.of("wiki"), SearchSpaces.all(), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactlyInAnyOrder("1", "2");
            assertThat(index.search("nachtlauf", Set.of("wiki", "docs"), new SearchSpaces(Set.of("OPS"), Set.of()),
                    10)).extracting(PageTextIndex.Match::pageId).containsExactly("2");
            assertThat(index.search("nachtlauf", Set.of("wiki"), new SearchSpaces(Set.of(), Set.of("OPS")), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactly("1");
            assertThat(index.search("Prüfung oder Urlaub", Set.of("wiki"), SearchSpaces.all(), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactly("1");
            assertThat(index.search(" ,; ", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.search("nachtlauf", Set.of(), SearchSpaces.all(), 10)).isEmpty();
        }
    }

    @Test
    void replacesRemovesAndSurvivesAReopen() {
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "alt"));
            index.put(entry("wiki", "1", "DEV", "A", "neu"));
            index.put(entry("wiki", "2", "DEV", "B", "neu"));
            index.put(entry("docs", "3", "docs", "C", "neu"));
            index.remove(new PageKey("wiki", "2"));
            index.removeSource("docs");

            assertThat(index.search("alt", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.inputSha256(new PageKey("wiki", "1"))).contains("sha256:neu");
        }
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            assertThat(index.search("neu", Set.of("wiki", "docs"), SearchSpaces.all(), 10))
                    .extracting(PageTextIndex.Match::pageId).containsExactly("1");
            assertThat(index.inputSha256(new PageKey("wiki", "2"))).isEmpty();
        }
    }

    @Test
    void anIndexInAnotherSchemaStartsEmpty() throws Exception {
        try (FSDirectory directory = FSDirectory.open(Files.createDirectories(dir));
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, "page-text-index/v0").entrySet());
            writer.commit();
        }
        try (LucenePageTextIndex index = LucenePageTextIndex.open(dir)) {
            index.put(entry("wiki", "1", "DEV", "A", "neu"));

            assertThat(index.search("neu", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
        }
    }

    private static PageTextIndex.Entry entry(String sourceId, String pageId, String spaceKey, String title,
                                             String text) {
        return new PageTextIndex.Entry(new PageKey(sourceId, pageId), spaceKey, title, text,
                "sha256:" + text.split(" ")[0]);
    }
}
