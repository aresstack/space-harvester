package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Je semantischem Absatz ein Embedding (#181, ADR 0020), wie {@code withPassages} im Enterprise AI Client. */
class PassageChunksTest {

    private final List<List<String>> calls = new ArrayList<>();
    private final EmbeddingProvider model = new PassageProcessorTest.TopicModel() {
        @Override
        public List<float[]> embed(List<String> texts) {
            calls.add(List.copyOf(texts));
            return super.embed(texts);
        }
    };
    private final EmbeddingProcessor plain = new EmbeddingProcessor(model, 1000,
            KnowledgeTextSegmentation.defaults());
    private final EmbeddingProcessor passages = plain.withPassages(new PassageChunks(new PassageSegmentation(
            SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()), KnowledgeTextSegmentation.defaults())));

    @Test
    void eachPassageGetsItsOwnVectorAndThePositionsOfItsText() {
        String text = PassageProcessorTest.TEXT.text();

        EmbeddingPayload payload = EmbeddingPayload.parse(passages.process(PassageProcessorTest.TEXT).payloadJson());

        assertThat(payload.chunks()).extracting(c -> text.substring(c.charStart(), c.charEnd())).containsExactly(
                "Die Katze schläft im Korb. Die Katze frisst am Abend. Die Katze spielt mit Wolle.",
                "Der Server startet um acht. Der Server läuft stabil. Der Server stoppt nachts.");
        // Erst die Sätze für die Themengrenze, dann je Absatz sein Text ohne Vorspann.
        assertThat(calls).hasSize(2);
        assertThat(calls.getLast()).containsExactly(
                "Die Katze schläft im Korb. Die Katze frisst am Abend. Die Katze spielt mit Wolle.",
                "Der Server startet um acht. Der Server läuft stabil. Der Server stoppt nachts.");
    }

    @Test
    void theSegmentationIsPartOfTheVersion() {
        assertThat(passages.version()).startsWith(plain.version()).contains("+passages:semantic-v2;window=3");
        assertThat(passages.id()).isEqualTo(plain.id());
    }
}
