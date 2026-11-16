package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ChangeEntries;
import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLogStore;
import com.aresstack.spaceharvester.application.changelog.ChangeQuery;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0030: Die Abfragen des Protokolls liegen im Index (Quelle, Art, Auslöser, Space, Suchtext, Zeitraum, Richtung,
 * seitenweise) und liefern dasselbe wie die Prüfung im Speicher.
 */
class LucenePageCacheChangeQueryTest {

    @TempDir
    Path directory;

    private LucenePageCache cache;
    /** Dieselbe Ablage, aber mit der Prüfung im Speicher aus {@link ChangeLogStore}. */
    private ChangeLogStore inMemory;
    private List<ChangeEntry> rows;

    @BeforeEach
    void fill() {
        cache = LucenePageCache.open(directory);
        cache.changeBatch = 2;
        ChangeTrigger.MANUAL.run(() -> cache.save(page("wiki", "101", "DEV", null, "1", json("{\"v\":1}"))));
        ChangeTrigger.BACKGROUND.run(() -> cache.save(page("wiki", "102", "DEV", "101", "1", json("{}"))));
        ChangeTrigger.SCHEDULE.run(() -> cache.save(page("wiki", "201", "OPS", null, "1", json("{}"))));
        ChangeTrigger.MANUAL.run(() -> cache.save(page("other", "101", "DEV", null, "1", json("{}"))));
        // Seite 101 wandert mit neuer Version nach OPS.
        ChangeTrigger.EVENT.run(() -> cache.save(page("wiki", "101", "OPS", null, "2", json("{\"v\":2}"),
                Instant.parse("2026-10-17T08:00:00Z"))));
        rows = cache.changeEntries("wiki");
        inMemory = new ChangeLogStore() {
            @Override
            public List<ChangeEntry> changeEntries(String sourceId) {
                return cache.changeEntries(sourceId);
            }

            @Override
            public void changeLogRecorded(Consumer<String> recorded) {
            }

            @Override
            public List<UpdateMark> marks(String sourceId) {
                return List.of();
            }

            @Override
            public void mark(UpdateMark mark) {
            }
        };
    }

    @AfterEach
    void close() {
        cache.close();
    }

    private static ChangeQuery query(Set<String> sources, Set<ChangeKind> kinds, Set<ChangeTrigger> triggers,
                                     String space, String text, Instant lower, boolean lowerInclusive,
                                     Instant upper, boolean upperInclusive, boolean newestFirst) {
        return new ChangeQuery(sources, kinds, triggers, space, text, lower, lowerInclusive, upper, upperInclusive,
                newestFirst);
    }

    private void same(ChangeQuery query, UnaryOperator<ChangeEntry> view, int offset, int limit) {
        ChangeEntries indexed = cache.changeEntries(query, view, offset, limit);
        ChangeEntries expected = inMemory.changeEntries(query, view, offset, limit);
        assertThat(indexed.entries()).as(query.toString()).isEqualTo(expected.entries());
        assertThat(indexed.total()).as(query.toString()).isEqualTo(expected.total());
    }

    @Test
    void theIndexAnswersLikeTheCheckInMemory() {
        assertThat(rows).extracting(ChangeEntry::kind).contains(ChangeKind.MOVED_OUT);
        Instant second = rows.get(1).at();
        Instant fourth = rows.get(3).at();
        Set<String> wiki = Set.of("wiki");
        List<ChangeQuery> queries = List.of(
                ChangeQuery.source("wiki"),
                query(Set.of("wiki", "other"), Set.of(), Set.of(), null, null, null, false, null, false, true),
                query(wiki, Set.of(ChangeKind.NEW), Set.of(), null, null, null, false, null, false, false),
                query(wiki, Set.of(), Set.of(ChangeTrigger.BACKGROUND, ChangeTrigger.SCHEDULE), null, null, null,
                        false, null, false, true),
                // Der alte Space findet die gewanderte Seite.
                query(wiki, Set.of(ChangeKind.MOVED_OUT), Set.of(), "DEV", null, null, false, null, false, true),
                query(wiki, Set.of(), Set.of(), "OPS", null, null, false, null, false, true),
                query(wiki, Set.of(), Set.of(), null, "seite 10", null, false, null, false, true),
                query(wiki, Set.of(), Set.of(), null, "201", null, false, null, false, true),
                query(wiki, Set.of(), Set.of(), null, "–", null, false, null, false, true),
                query(wiki, Set.of(), Set.of(), null, null, second, true, fourth, false, true),
                ChangeQuery.span("wiki", second, fourth));
        for (ChangeQuery query : queries) {
            same(query, entry -> entry, 0, Integer.MAX_VALUE);
        }
    }

    @Test
    void pagesCountOnlyVisibleRowsAcrossReadBatches() {
        UnaryOperator<ChangeEntry> without102 = entry -> entry.pageId().equals("102") ? null : entry;
        ChangeQuery newest = query(Set.of("wiki"), Set.of(), Set.of(), null, null, null, false, null, false, true);

        ChangeEntries first = cache.changeEntries(newest, without102, 0, 2);
        ChangeEntries second = cache.changeEntries(newest, without102, 2, 2);

        assertThat(first.total()).isEqualTo(rows.size() - 1);
        assertThat(first.entries()).hasSize(2);
        assertThat(first.entries().getFirst().at()).isEqualTo(rows.getLast().at());
        assertThat(second.entries()).extracting(ChangeEntry::pageId).doesNotContain("102");
        same(newest, without102, 2, 2);
        same(newest, without102, 1, 3);
    }
}
