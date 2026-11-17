package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.baseline;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.acquisition.FindingCataloging;
import com.aresstack.spaceharvester.application.holdings.HoldingsChanges;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Meldungen des Seiten-Cache an den Bestand (ADR 0027): nach jedem Commit die geänderten Seiten je Space, bei einer
 * neuen Generation der Space, beim Entfernen einer Quelle die Quelle; ohne Commit nichts.
 */
class LucenePageCacheChangesTest {

    @TempDir
    Path directory;

    private final Recorder recorder = new Recorder();

    @Test
    void everyWriteReportsItsPagesAndAMovedOrRemovedPageAlsoItsFormerSpaceAndParent() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            cache.changes(recorder);

            cache.save(page("wiki", "1", "DEV", null, "1", json("{}")));
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[1]");
            cache.saveHeader(page("wiki", "2", "DEV", "1", "1", json("{}")).header());
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[2]");
            cache.save(page("wiki", "3", "DEV", "1", "1", json("{}")));
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[3]");

            // Innerhalb des Space verschoben: die bisherige Elternseite verliert den Teilbaum, die Teilansicht zu den
            // gemeldeten Seiten erreicht sie nur, wenn sie mitgemeldet wird.
            cache.save(page("wiki", "3", "DEV", "2", "2", json("{}")));
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[1, 3]");

            cache.save(page("wiki", "1", "OPS", null, "2", json("{}")));
            assertThat(recorder.take()).containsExactlyInAnyOrder("pages:wiki/DEV:[1]", "pages:wiki/OPS:[1]");
            cache.saveHeader(page("wiki", "2", "OPS", null, "2", json("{}")).header());
            assertThat(recorder.take()).containsExactlyInAnyOrder("pages:wiki/DEV:[1, 2]", "pages:wiki/OPS:[2]");

            assertThat(cache.remove(new PageKey("wiki", "3"))).isTrue();
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[2, 3]");
            assertThat(cache.remove(new PageKey("wiki", "2"))).isTrue();
            assertThat(recorder.take()).containsExactly("pages:wiki/OPS:[2]");
        }
    }

    @Test
    void theOtherWritePathsReportToo() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            cache.changes(recorder);
            PageSnapshot first = page("wiki", "1", "DEV", null, "1", json("{}"));

            assertThat(cache.trySave(first, null)).isTrue();
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[1]");
            assertThat(cache.tryCatalog(page("wiki", "1", "DEV", null, "2", json("{}")).header()))
                    .isEqualTo(FindingCataloging.Outcome.OUTDATED);
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[1]");

            // Gesperrt, dann nach Ablauf der Frist endgültig entfernt: Space und Elternseite kommen aus dem
            // zurückgehaltenen Stand.
            cache.save(page("wiki", "2", "DEV", "1", "1", json("{}")));
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[2]");
            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.withhold(new PageKey("wiki", "2"));
                batch.commit();
            }
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[2]");
            assertThat(cache.remove(new PageKey("wiki", "2"))).isFalse();
            assertThat(recorder.take()).containsExactly("pages:wiki/DEV:[1, 2]");
        }
    }

    @Test
    void aGenerationReportsItsSpaceAndARemovedSourceItsSource() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            cache.changes(recorder);
            PageSnapshot first = page("wiki", "1", "DEV", null, "1", json("{}"));

            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.save(first);
                batch.save(page("wiki", "7", "OPS", null, "1", json("{}")));
                batch.confirm(baseline("wiki", "DEV", 1, first));
                batch.commit();
            }
            assertThat(recorder.take()).containsExactlyInAnyOrder("space:wiki/DEV", "pages:wiki/OPS:[7]");

            cache.removeSource("wiki");
            assertThat(recorder.take()).containsExactly("source:wiki");
        }
    }

    @Test
    void aBatchWithoutCommitReportsNothingAndAFailingReceiverKeepsTheCommit() {
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            cache.changes(recorder);
            try (LucenePageCache.Batch batch = cache.begin()) {
                batch.save(page("wiki", "1", "DEV", null, "1", json("{}")));
            }
            assertThat(recorder.take()).isEmpty();

            cache.changes(new Recorder() {
                @Override
                public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
                    throw new IllegalStateException("kaputt");
                }
            });
            PageSnapshot kept = page("wiki", "2", "DEV", null, "1", json("{}"));
            cache.save(kept);
            assertThat(cache.find(kept.key())).isPresent();
        }
    }

    /** Sammelt Meldungen als Text. */
    private static class Recorder implements HoldingsChanges {
        private final List<String> calls = new ArrayList<>();

        @Override
        public synchronized void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
            calls.add("pages:" + sourceId + "/" + spaceKey + ":" + new TreeSet<>(pageIds));
        }

        @Override
        public synchronized void spaceChanged(String sourceId, String spaceKey) {
            calls.add("space:" + sourceId + "/" + spaceKey);
        }

        @Override
        public synchronized void sourceChanged(String sourceId) {
            calls.add("source:" + sourceId);
        }

        @Override
        public synchronized void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
            calls.add("loading:" + sourceId + "/" + spaceKey + ":" + new TreeSet<>(pageIds));
        }

        synchronized List<String> take() {
            List<String> taken = List.copyOf(calls);
            calls.clear();
            return taken;
        }
    }
}
