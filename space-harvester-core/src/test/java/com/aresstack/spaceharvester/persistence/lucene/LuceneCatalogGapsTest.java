package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.baseline;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneCatalogGapsTest {

    @TempDir
    Path directory;

    @Test
    void findsPagesWithoutContentAndPagesWhoseHeaderIsNewerThanTheirContent() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            PageSnapshot current = page("wiki", "101", "DEV", null, "3", json("{}"));
            PageSnapshot stale = page("wiki", "102", "DEV", null, "1", json("{}"));
            cache.save(current);
            cache.save(stale);
            // Nur Kopfdaten: katalogisiert, nicht geladen.
            cache.saveHeader(page("wiki", "103", "DEV", null, "1", json("{}")).header());
            // Neuere Version laut Katalog, Inhalt noch alt.
            cache.saveHeader(page("wiki", "102", "DEV", null, "2", json("{}")).header());
            // Anderer Space.
            cache.saveHeader(page("wiki", "201", "OPS", null, "1", json("{}")).header());
            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.confirm(baseline("wiki", "DEV", 1, current, stale));
                batch.commit();
            }
            // Gesperrt: hat kein Seitendokument mehr.
            cache.save(page("wiki", "104", "DEV", null, "1", json("{}")));
            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.withhold(new PageKey("wiki", "104"));
                batch.commit();
            }

            LuceneCatalogGaps gaps = new LuceneCatalogGaps(cache);

            assertThat(gaps.spaces()).containsExactly(new SyncBaselineStore.SpaceRef("wiki", "DEV"));
            assertThat(gaps.gaps("wiki", "DEV", 10)).extracting(gap -> gap.key().pageId() + ":" + gap.kind())
                    .containsExactlyInAnyOrder("102:OUTDATED", "103:NOT_LOADED");
            assertThat(gaps.gaps("wiki", "DEV", 1)).hasSize(1);
        }
    }
}
