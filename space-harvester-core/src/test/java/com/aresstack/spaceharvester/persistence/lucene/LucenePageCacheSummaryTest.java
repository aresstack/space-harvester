package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.StoredSummary;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0030, Abschnitt 5: Zusammenfassungen liegen neben dem Protokoll, je Quelle und Update, mit dessen Aufbewahrung. */
class LucenePageCacheSummaryTest {

    @TempDir
    Path directory;

    private LucenePageCache cache;

    @BeforeEach
    void open() {
        cache = LucenePageCache.open(directory);
    }

    @AfterEach
    void close() {
        cache.close();
    }

    private static StoredSummary summary(String sourceId, String updateId, String text, Instant at) {
        return new StoredSummary(sourceId, updateId, text,
                List.of(new StoredSummary.Statement("Startseite neu", List.of("101", "102"), "Startseite",
                                "Ausführlich: die Startseite wurde neu gefasst."),
                        new StoredSummary.Statement("Ohne Seiten", List.of())),
                "test-summary:1", Instant.parse("2026-11-16T20:00:00Z"), at, 2, "sha256:ab",
                List.of(new StoredSummary.InputPage("DEV", "101", true), new StoredSummary.InputPage("OPS", "102")));
    }

    @Test
    void aSummaryIsStoredReplacedPerUpdateReportedAndInvisibleToPageCounts() {
        cache.save(page("wiki", "101", "DEV", null, "1", json("{}")));
        int documents = cache.documentCount();
        List<String> recorded = new CopyOnWriteArrayList<>();
        cache.changeLogRecorded(recorded::add);
        Instant end = Instant.parse("2026-11-16T19:00:00Z");

        cache.saveSummary(summary("wiki", "1763319600000000000", "Erste Fassung", end));
        cache.saveSummary(summary("wiki", "1763319600000000000", "Zweite Fassung", end));
        cache.saveSummary(summary("other", "open", "Andere Quelle", end));

        assertThat(cache.summary("wiki", "1763319600000000000", null)).contains(
                summary("wiki", "1763319600000000000", "Zweite Fassung", end));
        assertThat(cache.summary("wiki", "open", null)).isEmpty();
        assertThat(cache.summarizedUpdates("wiki", null)).containsExactly("1763319600000000000");
        assertThat(recorded).containsExactly("wiki", "wiki", "other");
        assertThat(cache.documentCount()).isEqualTo(documents);
    }

    @Test
    void summariesArePrunedWithTheChangeLogAndRemovedWithTheirSource() {
        cache.saveSummary(summary("wiki", "alt", "Alt", Instant.parse("2025-01-01T00:00:00Z")));
        cache.saveSummary(summary("wiki", "neu", "Neu", Instant.parse("2026-11-16T19:00:00Z")));
        cache.saveSummary(summary("other", "open", "Andere", Instant.parse("2026-11-16T19:00:00Z")));

        assertThat(cache.pruneChangeLog(Instant.parse("2026-01-01T00:00:00Z"))).isTrue();
        assertThat(cache.summarizedUpdates("wiki", null)).containsExactly("neu");

        cache.removeSource("wiki");
        assertThat(cache.summarizedUpdates("wiki", null)).isEmpty();
        assertThat(cache.summarizedUpdates("other", null)).containsExactly("open");
    }

    @Test
    void summariesAreStoredPerLevelAndTheSourceLevelKeepsItsId() {
        Instant end = Instant.parse("2026-11-16T19:00:00Z");
        StoredSummary source = summary("wiki", "u1", "Quelle", end);
        StoredSummary dev = new StoredSummary("wiki", "u1", "DEV", "Nur DEV", source.statements(), source.model(),
                source.createdAt(), end, 0, "sha256:cd", source.inputPages());

        cache.saveSummary(source);
        cache.saveSummary(dev);

        assertThat(cache.summary("wiki", "u1", null)).contains(source);
        assertThat(cache.summary("wiki", "u1", "DEV")).contains(dev);
        assertThat(cache.summary("wiki", "u1", "OPS")).isEmpty();
        assertThat(cache.summarizedUpdates("wiki", null)).containsExactly("u1");
        assertThat(cache.summarizedUpdates("wiki", "DEV")).containsExactly("u1");
        assertThat(cache.summaries("wiki")).containsExactlyInAnyOrder(source, dev);

        cache.deleteSummary("wiki", "u1", "DEV");
        assertThat(cache.summary("wiki", "u1", "DEV")).isEmpty();
        assertThat(cache.summary("wiki", "u1", null)).contains(source);
        cache.saveSummary(dev);
        cache.removeSource("wiki");
        assertThat(cache.summaries("wiki")).isEmpty();
    }
}
