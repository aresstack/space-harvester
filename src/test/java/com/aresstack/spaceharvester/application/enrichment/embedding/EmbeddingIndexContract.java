package com.aresstack.spaceharvester.application.enrichment.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vertrag jeder {@link EmbeddingIndex}-Umsetzung, die zugleich die {@link EnrichmentStore} ist, aus deren
 * {@code embedding}-Ergebnissen sie sucht. Umsetzungen erben und liefern eine leere Ablage.
 */
public abstract class EmbeddingIndexContract {

    protected static final String VERSION = "1+test-model+3+1500";
    protected static final PageKey NIGHT = new PageKey("wiki", "101");
    protected static final PageKey HOLIDAY = new PageKey("wiki", "102");
    protected static final PageKey OTHER_SOURCE = new PageKey("files", "101");

    protected abstract EnrichmentStore store();

    protected abstract EmbeddingIndex index();

    @Test
    void nearestChunksComeFirstWithTheirPositionsAndCosineSimilarity() {
        store().save(embedding(NIGHT, VERSION, "[1,0,0]", "[0,2,0]"));
        store().save(embedding(HOLIDAY, VERSION, "[0,0,5]"));

        List<EmbeddingIndex.ChunkHit> hits = index().nearest("wiki", VERSION, new float[] {0, 1, 0.1f}, 10);

        assertThat(hits).extracting(hit -> hit.page().pageId() + "#" + hit.chunk())
                .containsExactly("101#1", "102#0", "101#0");
        EmbeddingIndex.ChunkHit best = hits.get(0);
        assertThat(best.charStart()).isEqualTo(10);
        assertThat(best.charEnd()).isEqualTo(20);
        assertThat(best.inputSha256()).isEqualTo(sha("101"));
        assertThat(best.similarity()).isCloseTo(1 / Math.sqrt(1.01), within(1e-4));
        assertThat(hits.get(2).similarity()).isCloseTo(0, within(1e-4));
    }

    @Test
    void kLimitsTheHits() {
        store().save(embedding(NIGHT, VERSION, "[1,0,0]", "[0,1,0]"));
        store().save(embedding(HOLIDAY, VERSION, "[0,0,1]"));

        assertThat(index().nearest("wiki", VERSION, new float[] {1, 0.5f, 0.2f}, 2))
                .extracting(hit -> hit.page().pageId() + "#" + hit.chunk()).containsExactly("101#0", "101#1");
    }

    @Test
    void onlyTheSourceAndTheExactProcessorVersionAreSearched() {
        store().save(embedding(NIGHT, VERSION, "[1,0,0]"));
        store().save(embedding(OTHER_SOURCE, VERSION, "[1,0,0]"));
        store().save(embedding(HOLIDAY, "1+other-model+3+1500", "[1,0,0]"));

        assertThat(index().nearest("wiki", VERSION, new float[] {1, 0, 0}, 10))
                .extracting(EmbeddingIndex.ChunkHit::page).containsExactly(NIGHT);
    }

    @Test
    void aNewResultReplacesTheOldVectors() {
        store().save(embedding(NIGHT, VERSION, "[1,0,0]", "[0,1,0]"));
        store().save(embedding(NIGHT, VERSION, "[0,0,1]"));

        assertThat(index().nearest("wiki", VERSION, new float[] {1, 0, 0}, 10))
                .extracting(EmbeddingIndex.ChunkHit::chunk).containsExactly(0);
        assertThat(index().nearest("wiki", VERSION, new float[] {0, 0, 1}, 10).get(0).similarity())
                .isCloseTo(1, within(1e-4));
    }

    @Test
    void aFailedResultOrRemovedPageHasNoVectors() {
        store().save(embedding(NIGHT, VERSION, "[1,0,0]"));
        store().save(embedding(HOLIDAY, VERSION, "[1,0,0]"));
        store().save(new Enrichment(NIGHT, "embedding", VERSION, sha("101"), "2", Enrichment.Status.FAILED, "{}",
                List.of(), "processor_error", Instant.parse("2026-10-17T09:00:00Z")));
        store().removeAll(HOLIDAY);

        assertThat(index().nearest("wiki", VERSION, new float[] {1, 0, 0}, 10)).isEmpty();
    }

    @Test
    void otherProcessorsAndUnreadablePayloadsAreIgnored() {
        store().save(new Enrichment(NIGHT, "outline", VERSION, sha("101"), "2", Enrichment.Status.OK,
                payload("[1,0,0]"), List.of(), null, Instant.parse("2026-10-17T09:00:00Z")));
        store().save(new Enrichment(HOLIDAY, "embedding", VERSION, sha("102"), "2", Enrichment.Status.OK,
                "{\"model\":\"x\"}", List.of(), null, Instant.parse("2026-10-17T09:00:00Z")));

        assertThat(index().nearest("wiki", VERSION, new float[] {1, 0, 0}, 10)).isEmpty();
        assertThat(store().find(HOLIDAY)).hasSize(1);
    }

    @Test
    void theVectorsDoNotAppearAsResults() {
        Enrichment result = embedding(NIGHT, VERSION, "[1,0,0]", "[0,1,0]");
        store().save(result);

        assertThat(store().find(NIGHT)).containsExactly(result);
    }

    @Test
    void aZeroOrMismatchedQueryFindsNothingAndKIsBounded() {
        store().save(embedding(NIGHT, VERSION, "[1,0,0]"));

        assertThat(index().nearest("wiki", VERSION, new float[] {0, 0, 0}, 10)).isEmpty();
        assertThat(index().nearest("wiki", VERSION, new float[] {1, 0}, 10)).isEmpty();
        assertThatThrownBy(() -> index().nearest("wiki", VERSION, new float[] {1, 0, 0}, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index().nearest("wiki", VERSION, new float[] {1, 0, 0}, EmbeddingIndex.MAX_K + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    protected static Enrichment embedding(PageKey key, String version, String... vectors) {
        return new Enrichment(key, "embedding", version, sha(key.pageId()), "2", Enrichment.Status.OK,
                payload(vectors), List.of(), null, Instant.parse("2026-10-17T09:00:00Z"));
    }

    private static String payload(String... vectors) {
        StringBuilder json = new StringBuilder("{\"model\":\"test-model\",\"dimensions\":3,\"chunks\":[");
        for (int i = 0; i < vectors.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"charStart\":").append(i * 10).append(",\"charEnd\":")
                    .append(i * 10 + 10).append(",\"section\":null,\"vector\":").append(vectors[i]).append('}');
        }
        return json.append("]}").toString();
    }

    protected static String sha(String pageId) {
        return "sha256:" + String.valueOf(pageId.charAt(2)).repeat(64);
    }
}
