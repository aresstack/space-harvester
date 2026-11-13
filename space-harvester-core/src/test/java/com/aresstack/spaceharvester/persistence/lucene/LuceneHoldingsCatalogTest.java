package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.CONFIRMED;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.baseline;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rohstand des Bestands aus dem Lucene-Cache (ADR 0027): Katalog, Versionen, veraltete und gesperrte Seiten. */
class LuceneHoldingsCatalogTest {

    @TempDir
    Path directory;

    @Test
    void theStockNamesContentVersionsStaleAndLockedPagesWithTheirParents() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            PageSnapshot root = page("wiki", "1", "DEV", null, "3", json("{}"));
            PageSnapshot child = page("wiki", "3", "DEV", "1", "3", json("{}"));
            PageSnapshot stale = page("wiki", "4", "DEV", "3", "2", json("{}"));
            cache.save(root);
            cache.save(child);
            cache.save(stale);
            cache.save(page("wiki", "6", "DEV", "3", "1", json("{}")));
            // Nur Kopfdaten, und für 3 eine neuere Version laut Katalog.
            cache.saveHeader(page("wiki", "2", "DEV", "1", "1", json("{}")).header());
            cache.saveHeader(page("wiki", "3", "DEV", "1", "4", json("{}")).header());
            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.withhold(new PageKey("wiki", "6"));
                batch.confirm(new SyncBaseline("wiki", "DEV", 1, CONFIRMED,
                        baseline("wiki", "DEV", 1, root, child, stale).pages(), new TreeSet<>(java.util.Set.of("4")),
                        new TreeSet<>(), new TreeMap<>(Map.of("6", CONFIRMED))));
                batch.commit();
            }
            cache.saveHeader(page("wiki", "9", "OPS", null, "1", json("{}")).header());
            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.confirm(baseline("wiki", "EMPTY", 1));
                batch.commit();
            }

            LuceneHoldingsCatalog catalog = new LuceneHoldingsCatalog(cache);
            HoldingsCatalog.Stock dev = catalog.stock("wiki", "DEV").orElseThrow();
            HoldingsCatalog.Stock ops = catalog.stock("wiki", "OPS").orElseThrow();

            assertThat(dev.pages()).extracting(page -> page.pageId() + ":" + page.parentPageId() + ":" + page.loaded()
                            + ":" + page.version() + ":" + page.loadedVersion())
                    .containsExactlyInAnyOrder("1:null:true:3:3", "2:1:false:1:null", "3:1:true:4:3",
                            "4:3:true:2:2");
            assertThat(dev.stale()).containsExactly("4");
            assertThat(dev.locked()).containsExactly(new HoldingsCatalog.LockedPage("6", "3"));
            assertThat(dev.generation().id()).isEqualTo("g1");
            assertThat(dev.generation().syncedAt()).isEqualTo(CONFIRMED);
            assertThat(ops.generation().id()).as("ohne Inventur").isEqualTo("g0");
            assertThat(ops.generation().syncedAt()).isNull();
            assertThat(ops.pages()).extracting(HoldingsCatalog.CatalogPage::pageId).containsExactly("9");
            assertThat(catalog.stock("wiki", "NONE")).isEmpty();
            assertThat(catalog.spaceKeys("wiki")).containsExactly("DEV", "EMPTY", "OPS");
            assertThat(catalog.spaceKeys("andere")).isEmpty();
        }
    }
}
