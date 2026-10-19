package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.CONFIRMED;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.acquisition.FindingCataloging;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import java.nio.file.Path;
import java.time.Instant;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Katalogisieren von Funden im Lucene-Cache (ADR 0016): neue Seiten als Kopfdaten ohne Inhalt, neuere Versionen als
 * veraltet, gesperrte Seiten und gleiche Versionen unberührt; die Hintergrundfüllung findet das Ergebnis.
 */
class LuceneFindingStoreTest {

    private static final Instant LATER = Instant.parse("2026-10-17T08:00:00Z");

    @TempDir
    Path directory;

    @Test
    void aNewFindingBecomesAHeaderWithoutContentThatTheFillFinds() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            LuceneFindingStore store = new LuceneFindingStore(cache);
            PageKey key = new PageKey("wiki", "301");
            assertThat(store.entry(key)).isEmpty();

            assertThat(store.catalog(header("301", "DEV", "4", LATER))).isEqualTo(FindingCataloging.Outcome.NOT_LOADED);

            assertThat(store.entry(key)).contains(new FindingCataloging.Entry(false, "4"));
            assertThat(cache.find(key)).isEmpty();
            assertThat(cache.findHeader(key)).map(h -> h.metadata().partitionKey()).contains("DEV");
            confirm(cache, "DEV", new TreeMap<>());
            assertThat(new LuceneCatalogGaps(cache).gaps("wiki", "DEV", 10))
                    .extracting(gap -> gap.key().pageId() + ":" + gap.kind()).containsExactly("301:NOT_LOADED");
            // Dieselbe Version ändert nichts.
            assertThat(store.catalog(header("301", "DEV", "4", LATER.plusSeconds(1))))
                    .isEqualTo(FindingCataloging.Outcome.UNCHANGED);
        }
    }

    @Test
    void aNewerVersionOfALoadedPageMakesItOutdatedAndKeepsTheContent() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            LuceneFindingStore store = new LuceneFindingStore(cache);
            PageSnapshot loaded = page("wiki", "302", "DEV", null, "1", json("{}"));
            cache.save(loaded);

            assertThat(store.catalog(header("302", "DEV", "2", LATER))).isEqualTo(FindingCataloging.Outcome.OUTDATED);

            assertThat(cache.find(loaded.key())).map(PageSnapshot::revision).contains("1");
            assertThat(cache.findHeader(loaded.key())).map(PageHeader::revision).contains("2");
            assertThat(new LuceneCatalogGaps(cache).gaps("wiki", "DEV", 10))
                    .extracting(gap -> gap.key().pageId() + ":" + gap.kind()).containsExactly("302:OUTDATED");
        }
    }

    @Test
    void olderHeadersAndFindingsWithoutVersionReplaceNothing() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            LuceneFindingStore store = new LuceneFindingStore(cache);
            cache.saveHeader(header("303", "DEV", "5", LATER));

            assertThat(store.catalog(header("303", "DEV", "4", LATER.minusSeconds(60))))
                    .isEqualTo(FindingCataloging.Outcome.UNCHANGED);
            assertThat(store.catalog(header("303", "DEV", null, LATER.plusSeconds(60))))
                    .isEqualTo(FindingCataloging.Outcome.UNCHANGED);
            assertThat(store.entry(new PageKey("wiki", "303"))).contains(new FindingCataloging.Entry(false, "5"));
        }
    }

    @Test
    void aFetchRunningWhileTheFindingIsCataloguedIsStillSaved() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            PageSnapshot fetched = page("wiki", "306", "DEV", null, "1", json("{}"));
            String seen = cache.state(fetched.key());
            // Während des Abrufs katalogisiert die Suche dieselbe Seite mit denselben Kopfdaten.
            new LuceneFindingStore(cache).catalog(PageHeader.of("wiki", fetched.metadata(), LATER));

            assertThat(cache.trySave(fetched, seen)).isTrue();
            assertThat(cache.find(fetched.key())).isPresent();
        }
    }

    @Test
    void withheldPagesNeverComeBack() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            LuceneFindingStore store = new LuceneFindingStore(cache);
            // Zurückgehaltener Stand einer gesperrten Seite.
            cache.save(page("wiki", "304", "DEV", null, "1", json("{}")));
            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.withhold(new PageKey("wiki", "304"));
                batch.commit();
            }
            // Gesperrt in der bestätigten Generation, ohne Seitendokument.
            TreeMap<String, Instant> withheld = new TreeMap<>();
            withheld.put("305", CONFIRMED);
            confirm(cache, "DEV", withheld);

            assertThat(store.entry(new PageKey("wiki", "304"))).contains(new FindingCataloging.Entry(true, null));
            assertThat(store.catalog(header("304", "DEV", "2", LATER))).isEqualTo(FindingCataloging.Outcome.WITHHELD);
            assertThat(store.catalog(header("305", "DEV", "1", LATER))).isEqualTo(FindingCataloging.Outcome.WITHHELD);
            assertThat(cache.findHeader(new PageKey("wiki", "304"))).isEmpty();
            assertThat(cache.findHeader(new PageKey("wiki", "305"))).isEmpty();
        }
    }

    private static PageHeader header(String pageId, String spaceKey, String version, Instant observedAt) {
        PageSnapshot page = page("wiki", pageId, spaceKey, null, version, json("{}"));
        return PageHeader.of("wiki", page.metadata(), observedAt);
    }

    private static void confirm(LucenePageCache cache, String spaceKey, TreeMap<String, Instant> withheld) {
        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.confirm(new SyncBaseline("wiki", spaceKey, 1, CONFIRMED, new TreeMap<>(), new TreeSet<>(),
                    new TreeSet<>(), withheld));
            batch.commit();
        }
    }
}
