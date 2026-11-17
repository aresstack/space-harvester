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

    @Test
    void aRunHoldsOnlyABoundedNumberOfPagesAndBytesForDiffsAndStillCommitsEverything() {
        LucenePageCache cache = open();
        for (int i = 1; i <= 4; i++) {
            cache.save(page("wiki", "10" + i, "DEV", null, "1", json("Alt " + i)));
        }
        cache.diffBudget(2, 1024);

        try (LucenePageCache.Batch batch = cache.begin()) {
            for (int i = 1; i <= 4; i++) {
                batch.save(page("wiki", "10" + i, "DEV", null, "2", json("Neu " + i), LATER));
            }
            batch.commit();
        }

        List<Integer> diffed = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            if (!cache.contentChanges(new PageKey("wiki", "10" + i), null, 10).isEmpty()) {
                diffed.add(i);
            }
            assertThat(cache.findHeader(new PageKey("wiki", "10" + i))).map(PageHeader::metadata)
                    .map(PageMetadata::version).contains("2");
        }
        assertThat(diffed).containsExactly(1, 2);

        cache.diffBudget(10, 6);
        cache.save(page("wiki", "101", "DEV", null, "3", json("Zu lang")));
        assertThat(cache.contentChanges(new PageKey("wiki", "101"), null, 10)).hasSize(1);
    }

    @Test
    void aNewVersionWithIdenticalBytesIsAnEmptyDiff() {
        LucenePageCache cache = open();
        cache.save(version("1", "Gleich"));

        cache.save(page("wiki", "101", "DEV", null, "2", json("Gleich"), LATER));

        assertThat(diffs(cache)).singleElement().satisfies(change -> assertThat(change.paragraphs()).isEmpty());
    }

    /** Confluence-Fassung mit Storage-Text, Bearbeiter in version.by und Labels in den Kopfdaten. */
    private static PageSnapshot confluence(String version, String storage, String editor, String labels) {
        PageMetadata metadata = new PageMetadata("101", "confluence:wiki/page/101", "Seite 101", "DEV", null, version,
                Instant.parse("2026-10-15T10:30:00Z"), java.net.URI.create("https://wiki.example.org/101"),
                PageMetadata.sorted(java.util.Map.of("confluence.labels", labels)));
        String body = "{\"version\":{\"number\":" + version + ",\"by\":{\"displayName\":\"" + editor
                + "\",\"username\":\"u" + version + "\"}},\"body\":{\"storage\":{\"value\":\"" + storage
                + "\"}}}";
        return PageSnapshot.of("wiki", metadata, new com.aresstack.spaceharvester.application.snapshot.NativePayload(
                body.getBytes(StandardCharsets.UTF_8), "application/json",
                "confluence-rest-content+json;expand=body.storage,version"), LATER);
    }

    @Test
    void theRowNamesTheEditorAndLabelsAndTheDiffTheHeadingPath() {
        LucenePageCache cache = LucenePageCache.open(directory);
        opened.add(cache);
        cache.contentDiffer(new TextContentDiffer(
                new com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector()));
        cache.editors(new com.aresstack.spaceharvester.source.confluence.ConfluenceEditors());
        cache.save(confluence("1", "<p>Vorwort</p><h1>Betrieb</h1><p>Alt</p>", "Ada Muster", "api,intern"));

        cache.save(confluence("2", "<p>Vorwort</p><h1>Betrieb</h1><p>Neu</p>", "Bea Beispiel", "api"));

        List<com.aresstack.spaceharvester.application.changelog.ChangeEntry> rows = cache.changeEntries("wiki");
        assertThat(rows).extracting(com.aresstack.spaceharvester.application.changelog.ChangeEntry::editor)
                .containsExactly("Ada Muster", "Bea Beispiel", "Bea Beispiel");
        assertThat(rows.getLast().labels()).containsExactly("api");
        assertThat(rows.getFirst().labels()).containsExactly("api", "intern");
        assertThat(diffs(cache)).singleElement().satisfies(change -> assertThat(change.paragraphs())
                .containsExactly(new ParagraphChange(Kind.CHANGED, "Alt", "Neu", List.of("Betrieb"))));

        cache.close();
        opened.clear();
        LucenePageCache reopened = LucenePageCache.open(directory);
        opened.add(reopened);
        assertThat(reopened.changeEntries("wiki").getLast().editor()).isEqualTo("Bea Beispiel");
        assertThat(reopened.contentChanges(KEY, null, 10).getFirst().paragraphs().getFirst().headings())
                .containsExactly("Betrieb");
    }

    @Test
    void unchangedContentIsNotReadForAnEditorAndTheTitleSearchNeverFindsEditors() {
        LucenePageCache cache = open();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        cache.editors(snapshot -> {
            reads.incrementAndGet();
            return Optional.of("Ada");
        });
        cache.save(version("1", "A"));
        cache.save(version("1", "A"));

        assertThat(reads).hasValue(1);
        assertThat(cache.changeEntries(new com.aresstack.spaceharvester.application.changelog.ChangeQuery(
                java.util.Set.of("wiki"), java.util.Set.of(), java.util.Set.of(), null, "Ada", null, false, null,
                false, false), entry -> entry, 0, 10).total()).isZero();
    }
}
