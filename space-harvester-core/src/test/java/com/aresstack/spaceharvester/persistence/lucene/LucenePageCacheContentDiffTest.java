package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange.Kind;
import com.aresstack.spaceharvester.application.changelog.TextContentDiffer;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0030, Abschnitt 4 (CL5): Unterschied der Lesetexte beim Ersetzen eines Inhalts im Cache. */
class LucenePageCacheContentDiffTest {

    private static final Instant LATER = Instant.parse("2026-10-17T08:00:00Z");
    private static final PageKey KEY = new PageKey("wiki", "101");

    @TempDir
    Path directory;

    private final List<LucenePageCache> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(LucenePageCache::close);
    }

    /** Lesetext = die Payload als Text; Zeilen sind Absätze. */
    private LucenePageCache open() {
        LucenePageCache cache = LucenePageCache.open(directory);
        cache.contentDiffer(new TextContentDiffer(snapshot -> Optional.of(new PageText("test",
                new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()))));
        opened.add(cache);
        return cache;
    }

    private static PageSnapshot version(String version, String text) {
        return page("wiki", "101", "DEV", null, version, json(text), LATER);
    }

    private static List<ContentChange> diffs(LucenePageCache cache) {
        return cache.contentChanges(KEY, null, 100);
    }

    @Test
    void aNewVersionReplacingStoredContentLeavesTheDiffOfTheReadingTexts() {
        LucenePageCache cache = open();
        cache.save(version("1", "Titel\nAlt\nEnde"));

        cache.save(version("2", "Titel\nNeu\nEnde"));

        assertThat(diffs(cache)).singleElement().satisfies(change -> {
            assertThat(change.fromVersion()).isEqualTo("1");
            assertThat(change.toVersion()).isEqualTo("2");
            assertThat(change.paragraphs()).containsExactly(new ParagraphChange(Kind.CHANGED, "Alt", "Neu"));
            assertThat(change.truncated()).isFalse();
        });
    }

    @Test
    void theFirstLoadLeavesNoDiff() {
        LucenePageCache cache = open();

        cache.save(version("1", "Titel"));

        assertThat(diffs(cache)).isEmpty();
    }

    @Test
    void otherRawBytesOfTheSameVersionLeaveNoDiff() {
        LucenePageCache cache = open();
        cache.save(version("1", "Titel\nMakro 08:00"));

        cache.save(version("1", "Titel\nMakro 08:05"));

        assertThat(diffs(cache)).isEmpty();
    }

    @Test
    void aVersionSeenInTheCatalogAndLoadedLaterIsDiffedAgainstTheContentStillStored() {
        LucenePageCache cache = open();
        cache.save(version("1", "Titel\nAlt"));
        PageSnapshot second = version("2", "Titel\nNeu");

        cache.saveHeader(second.header());
        assertThat(diffs(cache)).isEmpty();
        cache.save(second);

        assertThat(diffs(cache)).singleElement().satisfies(change -> {
            assertThat(change.fromVersion()).isEqualTo("1");
            assertThat(change.toVersion()).isEqualTo("2");
            assertThat(change.paragraphs()).containsExactly(new ParagraphChange(Kind.CHANGED, "Alt", "Neu"));
        });
    }

    @Test
    void aNewVersionWithTheSameReadingTextIsAnEmptyDiffNotAMissingOne() {
        LucenePageCache cache = open();
        cache.save(version("1", "Titel"));

        cache.save(page("wiki", "101", "DEV", null, "2", json("Titel\n"), LATER));

        assertThat(diffs(cache)).singleElement().satisfies(change -> assertThat(change.paragraphs()).isEmpty());
    }

    @Test
    void contentWithoutVersionIsDiffedWhenItsHashChanges() {
        LucenePageCache cache = open();
        cache.save(page("wiki", "101", "DEV", null, null, json("Alt")));

        cache.save(page("wiki", "101", "DEV", null, null, json("Neu"), LATER));

        assertThat(diffs(cache)).singleElement().satisfies(change -> assertThat(change.paragraphs())
                .containsExactly(new ParagraphChange(Kind.CHANGED, "Alt", "Neu")));
    }

    @Test
    void diffsAreReturnedOldestFirstAfterTheGivenTimeAndSurviveARestart() {
        LucenePageCache cache = open();
        cache.save(version("1", "A"));
        cache.save(version("2", "B"));
        Instant between = cache.contentChanges(KEY, null, 1).getFirst().at();
        cache.save(version("3", "C"));
        cache.close();
        opened.clear();

        LucenePageCache reopened = open();

        assertThat(diffs(reopened)).extracting(ContentChange::toVersion).containsExactly("2", "3");
        assertThat(reopened.contentChanges(KEY, between, 100)).extracting(ContentChange::toVersion)
                .containsExactly("3");
        assertThat(reopened.contentChanges(KEY, null, 1)).hasSize(1);
    }

    @Test
    void withoutADifferNothingIsDiffed() {
        LucenePageCache cache = LucenePageCache.open(directory);
        opened.add(cache);
        cache.save(version("1", "A"));

        cache.save(version("2", "B"));

        assertThat(diffs(cache)).isEmpty();
    }

    @Test
    void diffsStayOutOfPageQueriesAndCounters() {
        LucenePageCache cache = open();
        cache.save(version("1", "A"));
        int documents = cache.documentCount();

        cache.save(version("2", "B"));

        assertThat(diffs(cache)).hasSize(1);
        assertThat(cache.documentCount()).as("nur die neue Revision zählt").isEqualTo(documents + 1);
        assertThat(cache.revisions(KEY)).hasSize(2);
    }

    @Test
    void removingOrWithholdingThePageOrRemovingTheSourceRemovesItsDiffs() {
        LucenePageCache cache = open();
        cache.save(version("1", "A"));
        cache.save(version("2", "B"));
        cache.save(page("wiki", "102", "DEV", null, "1", json("X")));
        cache.save(page("wiki", "102", "DEV", null, "2", json("Y"), LATER));
        cache.save(page("other", "101", "DEV", null, "1", json("X")));
        cache.save(page("other", "101", "DEV", null, "2", json("Y"), LATER));

        cache.remove(KEY);
        assertThat(diffs(cache)).isEmpty();
        assertThat(cache.contentChanges(new PageKey("wiki", "102"), null, 10)).hasSize(1);

        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.withhold(new PageKey("wiki", "102"));
            batch.commit();
        }
        assertThat(cache.contentChanges(new PageKey("wiki", "102"), null, 10)).isEmpty();

        cache.removeSource("other");
        assertThat(cache.contentChanges(new PageKey("other", "101"), null, 10)).isEmpty();
    }

    @Test
    void pruningRemovesDiffsLikeRows() {
        LucenePageCache cache = open();
        cache.save(version("1", "A"));
        cache.save(version("2", "B"));
        Instant written = diffs(cache).getFirst().at();

        cache.pruneChangeLog(written);
        assertThat(diffs(cache)).hasSize(1);

        assertThat(cache.pruneChangeLog(written.plusNanos(1))).isTrue();
        assertThat(diffs(cache)).isEmpty();
        assertThat(cache.findHeader(KEY)).isPresent();
    }

    @Test
    void anUnreadableFormatLeavesNoDiffAndNoFailure() {
        LucenePageCache cache = LucenePageCache.open(directory);
        opened.add(cache);
        cache.contentDiffer(new TextContentDiffer(snapshot -> Optional.empty()));
        cache.save(version("1", "A"));

        cache.save(version("2", "B"));

        assertThat(diffs(cache)).isEmpty();
        assertThat(cache.findHeader(KEY)).map(PageHeader::metadata).map(PageMetadata::version).contains("2");
    }
}
