package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStoreContract;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.facts.FactStoreContract;
import com.aresstack.spaceharvester.application.facts.FactTestResults;
import com.aresstack.spaceharvester.application.facts.FactTypes;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndexContract;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

    @Nested
    class AsEmbeddingIndex extends EmbeddingIndexContract {

        @Override
        protected EnrichmentStore store() {
            return store;
        }

        @Override
        protected EmbeddingIndex index() {
            return store;
        }

        @Test
        void vectorsSurviveReopening() {
            store.save(embedding(NIGHT, VERSION, "[1,0,0]"));
            store.close();

            store = LuceneEnrichmentStore.open(directory);

            assertThat(store.nearest("wiki", VERSION, new float[] {1, 0, 0}, 1))
                    .extracting(EmbeddingIndex.ChunkHit::page).containsExactly(NIGHT);
        }
    }

    @Nested
    class AsFactStore extends FactStoreContract {

        @Override
        protected EnrichmentStore store() {
            return store;
        }

        @Test
        void factsSurviveReopeningAndGoWithTheirSource() {
            store.save(FactTestResults.acronyms(NIGHT, FactTestResults.TEXT));
            store.save(FactTestResults.acronyms(FILE, FactTestResults.TEXT));
            store.close();

            store = LuceneEnrichmentStore.open(directory);
            assertThat(store.factsOf(NIGHT)).hasSize(2);

            store.removeSource("wiki");
            assertThat(store.factsOf(NIGHT)).isEmpty();
            assertThat(store.factsOf(FILE)).hasSize(2);
        }
    }

    @Test
    void aVersionTwoIndexGetsTheFactsOfItsResults() throws Exception {
        store.close();
        Path old = directory.resolve("v2");
        Enrichment acronyms = FactTestResults.acronyms(new PageKey("wiki", "101"), FactTestResults.TEXT);
        try (FSDirectory dir = FSDirectory.open(old);
                IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document doc = v1Document("acronyms", acronyms.payloadJson());
            doc.removeFields("processorVersion");
            doc.removeFields("inputSha256");
            doc.add(new StoredField("processorVersion", acronyms.processorVersion()));
            doc.add(new StoredField("inputSha256", acronyms.inputSha256()));
            writer.addDocument(doc);
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, LuceneEnrichmentStore.VERSION_2).entrySet());
            writer.commit();
        }

        store = LuceneEnrichmentStore.open(old);

        assertThat(store.factsOf(new PageKey("wiki", "101"))).extracting(MetadataFact::type)
                .containsExactly(FactTypes.ACRONYM_CANDIDATE, FactTypes.ACRONYM_DEFINITION);
        store.close();
        store = LuceneEnrichmentStore.open(old);
        assertThat(store.factsOf(new PageKey("wiki", "101"))).hasSize(2);
    }

    @Test
    void aVersionOneIndexIsUpgradedAndDropsOnlyItsEmbeddings() throws Exception {
        store.close();
        Path old = directory.resolve("v1");
        try (FSDirectory dir = FSDirectory.open(old);
                IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            writer.addDocument(v1Document("outline", "{\"words\":3}"));
            writer.addDocument(v1Document("embedding",
                    "{\"model\":\"m\",\"dimensions\":1,\"chunks\":[{\"charStart\":0,\"charEnd\":1,\"vector\":[1]}]}"));
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, LuceneEnrichmentStore.VERSION_1).entrySet());
            writer.commit();
        }

        store = LuceneEnrichmentStore.open(old);

        assertThat(store.find(new PageKey("wiki", "101"))).extracting(Enrichment::processorId)
                .containsExactly("outline");
        try (FSDirectory dir = FSDirectory.open(old)) {
            assertThat(SegmentInfos.readLatestCommit(dir).getUserData().get(PageCacheSchema.SCHEMA_KEY))
                    .isEqualTo(LuceneEnrichmentStore.VERSION);
        }
    }

    private static Document v1Document(String processorId, String payload) {
        Document doc = new Document();
        doc.add(new StringField("id", "wiki\u0000101\u0000" + processorId, Field.Store.NO));
        doc.add(new StringField("page", "wiki\u0000101", Field.Store.NO));
        doc.add(new StoredField("sourceId", "wiki"));
        doc.add(new StoredField("pageId", "101"));
        doc.add(new StoredField("processorId", processorId));
        doc.add(new StoredField("processorVersion", "1"));
        doc.add(new StoredField("inputSha256", "sha256:" + "1".repeat(64)));
        doc.add(new StoredField("status", "OK"));
        doc.add(new StoredField("payloadJson", payload));
        doc.add(new StoredField("computedAt", "2026-10-16T22:00:00Z"));
        return doc;
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
