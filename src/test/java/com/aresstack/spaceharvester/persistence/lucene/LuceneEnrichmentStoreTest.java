package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStoreContract;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneEnrichmentStoreTest extends EnrichmentStoreContract {

    @TempDir
    Path directory;

    private LuceneEnrichmentStore store;

    @BeforeEach
    void open() {
        store = LuceneEnrichmentStore.open(directory);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Override
    protected EnrichmentStore store() {
        return store;
    }

    @Test
    void resultsSurviveReopening() {
        Enrichment result = result(new PageKey("wiki", "101"));
        store.save(result);
        store.close();

        store = LuceneEnrichmentStore.open(directory);

        assertThat(store.find(new PageKey("wiki", "101"))).containsExactly(result);
    }

    @Test
    void aSecondProcessCannotOpenTheSameStore() {
        assertThatThrownBy(() -> LuceneEnrichmentStore.open(directory)).isInstanceOf(PageCacheException.class)
                .hasMessageContaining("anderen Prozess");
    }

    @Test
    void anIndexInAnotherSchemaIsRefused() {
        Path jobs = directory.resolve("jobs");
        LuceneJobStore.open(jobs).close();

        assertThatThrownBy(() -> LuceneEnrichmentStore.open(jobs)).isInstanceOf(PageCacheException.class)
                .hasMessageContaining("job-store/v1");
    }

    @Test
    void aClosedStoreRefusesAccess() {
        store.close();

        assertThatThrownBy(() -> store.find(new PageKey("wiki", "101"))).isInstanceOf(IllegalStateException.class);
    }

    private static Enrichment result(PageKey key) {
        return new Enrichment(key, "outline", "1", "sha256:" + "1".repeat(64), "4", Enrichment.Status.OK,
                "{\"words\":3}", List.of("Hinweis"), null, Instant.parse("2026-10-16T22:00:00Z"));
    }
}
