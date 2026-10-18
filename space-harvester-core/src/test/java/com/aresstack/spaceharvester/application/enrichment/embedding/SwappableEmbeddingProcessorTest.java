package com.aresstack.spaceharvester.application.enrichment.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Ein austauschbares Embedding-Modell (ADR 0015): Wechsel ändert die Version, ohne Modell bettet nichts ein. */
class SwappableEmbeddingProcessorTest {

    private static EmbeddingProvider model(String id, int dimensions) {
        return new EmbeddingProvider() {
            @Override
            public String modelId() {
                return id;
            }

            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[dimensions]).toList();
            }
        };
    }

    @Test
    void aNewModelMakesTheOldVectorsStaleAndNoModelEmbedsNothing() {
        AtomicReference<EmbeddingProvider> current = new AtomicReference<>(model("a", 3));
        EmbeddingProcessor processor = new EmbeddingProcessor(current::get, 1500,
                KnowledgeTextSegmentation.of(350, 1), sourceId -> true);
        String first = processor.version();
        assertThat(processor.appliesTo(new PageKey("wiki", "1"))).isTrue();

        current.set(model("b", 4));
        assertThat(processor.version()).isNotEqualTo(first).contains("+b+4+");

        current.set(null);
        assertThat(processor.available()).isFalse();
        assertThat(processor.version()).isEqualTo("off");
        assertThat(processor.appliesTo(new PageKey("wiki", "1"))).isFalse();
        assertThatThrownBy(() -> processor.embedQuery("frage")).isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void aSearchKeepsItsModelWhenTheConfigurationSwapsIt() {
        AtomicReference<EmbeddingProvider> current = new AtomicReference<>(model("a", 3));
        EmbeddingProcessor processor = new EmbeddingProcessor(current::get, 1500,
                KnowledgeTextSegmentation.of(350, 1), sourceId -> true);
        EmbeddingProcessor.Generation search = processor.current().orElseThrow();
        String version = search.version();

        current.set(model("b", 4));

        // Anfragevektor und gesuchte Version bleiben beim Modell vom Beginn der Suche.
        assertThat(search.version()).isEqualTo(version).contains("+a+3+");
        assertThat(search.embedQuery("frage")).hasSize(3);
        assertThat(processor.current().orElseThrow().version()).contains("+b+4+");
        current.set(null);
        assertThat(processor.current()).isEmpty();
    }

    @Test
    void anUnusableModelIsRefusedBeforeItIsUsed() {
        assertThatThrownBy(() -> EmbeddingProcessor.check(model("a b", 3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingProcessor.check(model("a", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
