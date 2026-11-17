package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Liste der für die KI freigegebenen Seiten in Lucene neben dem Cache (ADR 0028). */
class LucenePageReleasesTest {

    private static final Instant AT = Instant.parse("2026-11-13T19:31:00Z");

    @TempDir
    Path directory;

    @Test
    void entriesSurviveARestartInTheOrderTheyWereReleased() {
        try (LucenePageReleases releases = LucenePageReleases.open(directory)) {
            releases.add(List.of(entry("wiki", "2", "1"), entry("wiki", "1", "1")));
            releases.add(List.of(entry("other", "9", null)));
        }

        try (LucenePageReleases releases = LucenePageReleases.open(directory)) {
            assertThat(releases.entries()).containsExactly(entry("wiki", "2", "1"), entry("wiki", "1", "1"),
                    entry("other", "9", null));
            assertThat(releases.contains(new PageKey("wiki", "1"))).isTrue();
            assertThat(releases.contains(new PageKey("wiki", "3"))).isFalse();
            assertThat(releases.entries("other")).containsExactly(entry("other", "9", null));
        }
    }

    @Test
    void anAlreadyReleasedPageKeepsItsFirstEntry() {
        try (LucenePageReleases releases = LucenePageReleases.open(directory)) {
            releases.add(List.of(entry("wiki", "1", "1")));

            PageReleases.Entry later = new PageReleases.Entry(new PageKey("wiki", "1"), "DEV", null,
                    PageReleases.CHAT, AT.plusSeconds(60));
            assertThat(releases.add(List.of(later, entry("wiki", "2", null), entry("wiki", "2", null))))
                    .containsExactly(entry("wiki", "2", null));
            assertThat(releases.entries()).containsExactly(entry("wiki", "1", "1"), entry("wiki", "2", null));
        }
    }

    @Test
    void removingAndPurgingASourceAreStoredAndReported() {
        List<PageReleases.Change> changes = new ArrayList<>();
        try (LucenePageReleases releases = LucenePageReleases.open(directory)) {
            releases.subscribe(changes::add);
            releases.add(List.of(entry("wiki", "1", null), entry("wiki", "2", null), entry("other", "9", null)));

            assertThat(releases.remove(List.of(new PageKey("wiki", "1"), new PageKey("wiki", "7"))))
                    .containsExactly(entry("wiki", "1", null));
            releases.removeSource("other");
            assertThat(releases.remove(List.of(new PageKey("wiki", "1")))).isEmpty();
        }

        assertThat(changes).containsExactly(
                new PageReleases.Change(List.of(entry("wiki", "1", null), entry("wiki", "2", null),
                        entry("other", "9", null)), List.of()),
                new PageReleases.Change(List.of(), List.of(entry("wiki", "1", null))),
                new PageReleases.Change(List.of(), List.of(entry("other", "9", null))));
        try (LucenePageReleases releases = LucenePageReleases.open(directory)) {
            assertThat(releases.entries()).containsExactly(entry("wiki", "2", null));
        }
    }

    @Test
    void anIndexInAnotherSchemaIsNotOpened() throws Exception {
        try (FSDirectory foreign = FSDirectory.open(directory);
             IndexWriter writer = new IndexWriter(foreign, new IndexWriterConfig(new StandardAnalyzer()))) {
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, "fill-queue/v1").entrySet());
            writer.commit();
        }

        assertThatThrownBy(() -> LucenePageReleases.open(directory)).isInstanceOf(PageCacheException.class)
                .hasMessageContaining(LucenePageReleases.VERSION);
    }

    @Test
    void aClosedListWritesNothing() {
        LucenePageReleases releases = LucenePageReleases.open(directory);
        releases.close();

        assertThatThrownBy(() -> releases.add(List.of(entry("wiki", "1", null))))
                .isInstanceOf(PageCacheException.class);
        assertThat(releases.contains(new PageKey("wiki", "1"))).isFalse();
    }

    @Test
    void aPageOfTheListMayGoToTheAiEvenIfItsSourceIsNotReleased() {
        try (LucenePageReleases releases = LucenePageReleases.open(directory)) {
            AiRelease release = new AiRelease("open"::equals, releases);
            releases.add(List.of(entry("wiki", "1", null)));

            assertThat(release.mayProcess(new PageKey("wiki", "1"))).isTrue();
            assertThat(release.mayProcess(new PageKey("wiki", "2"))).isFalse();
            assertThat(release.mayProcess(new PageKey("open", "2"))).isTrue();
            assertThat(release.mayProcess("wiki", null)).isFalse();
            assertThat(release.sourceReleased("wiki")).isFalse();

            releases.remove(List.of(new PageKey("wiki", "1")));
            assertThat(release.mayProcess(new PageKey("wiki", "1"))).isFalse();
        }
    }

    @Test
    void aFailingConfigurationReleasesNoSource() {
        AiRelease release = AiRelease.ofSources(sourceId -> {
            throw new IllegalStateException("Konfiguration nicht lesbar");
        });

        assertThat(release.mayProcess(new PageKey("wiki", "1"))).isFalse();
    }

    private static PageReleases.Entry entry(String sourceId, String pageId, String node) {
        return new PageReleases.Entry(new PageKey(sourceId, pageId), "DEV", node, PageReleases.CHAT, AT);
    }
}
