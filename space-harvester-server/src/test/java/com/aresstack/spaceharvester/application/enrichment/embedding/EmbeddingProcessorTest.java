package com.aresstack.spaceharvester.application.enrichment.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Embeddings als Anreicherung: Zerlegung, Ergebnisformat, Validierung und Invalidierung bei Modellwechsel. */
class EmbeddingProcessorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void embedsEachSectionAndRecordsWhereTheChunkCameFrom() {
        String text = "Vorwort\nZiele\nDrei Ziele.\nBetrieb\nNachts.";
        int ziele = text.indexOf("Ziele");
        int betrieb = text.indexOf("Betrieb");
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Ziele", 1, ziele, betrieb),
                new PageText.Section("Betrieb", 1, betrieb, text.length())));
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("fake-model@1", 4);

        EnrichmentOutput output = new EmbeddingProcessor(provider, 100).process(page);

        assertThat(provider.calls).containsExactly(List.of("Vorwort\n", "Ziele\nDrei Ziele.\n", "Betrieb\nNachts."));
        assertThat(output.payloadJson()).startsWith("{\"model\":\"fake-model@1\",\"dimensions\":4,\"chunks\":[")
                .contains("{\"charStart\":0,\"charEnd\":8,\"section\":null,\"vector\":[")
                .contains("{\"charStart\":" + ziele + ",\"charEnd\":" + betrieb + ",\"section\":\"Ziele\"");
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    void longSectionsAreSplitAtLineOrWordBoundaries() {
        String text = ("wort ".repeat(30) + "\n").repeat(3);
        EmbeddingProcessor processor = new EmbeddingProcessor(new FakeEmbeddingProvider("m", 2), 200);

        List<EmbeddingProcessor.Chunk> chunks = processor.chunks(new PageText("t/v1", text, List.of()));

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.end() - chunk.start()).isLessThanOrEqualTo(200);
            assertThat(text.charAt(chunk.end() - 1)).isIn('\n', ' ');
        });
        assertThat(chunks.get(0).start()).isZero();
        assertThat(chunks.get(chunks.size() - 1).end()).isEqualTo(text.length());
    }

    @Test
    void anEmptyPageCallsNoModel() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);

        assertThat(new EmbeddingProcessor(provider, 100).process(new PageText("t/v1", " \n", List.of()))
                .payloadJson()).isEqualTo("{\"model\":\"m\",\"dimensions\":2,\"chunks\":[]}");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void aModelAnsweringWithWrongVectorsFailsOnlyThisProcessor() {
        EmbeddingProvider wrongDimension = provider("m", 3, texts -> texts.stream().map(t -> new float[2]).toList());
        EmbeddingProvider notFinite = provider("m", 1,
                texts -> texts.stream().map(t -> new float[] {Float.NaN}).toList());
        EmbeddingProvider missing = provider("m", 1, texts -> List.of());
        PageText page = new PageText("t/v1", "Text", List.of());

        for (EmbeddingProvider provider : List.of(wrongDimension, notFinite, missing)) {
            assertThatThrownBy(() -> new EmbeddingProcessor(provider, 100).process(page))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void invalidModelsAreRefusedUpFront() {
        assertThatThrownBy(() -> new EmbeddingProcessor(new FakeEmbeddingProvider("mit leerzeichen", 4), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProcessor(new FakeEmbeddingProvider("m", 0), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProcessor(new FakeEmbeddingProvider("m", 4), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewModelMakesOnlyTheEmbeddingsStale() {
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        PageSnapshot page = EnrichmentTestPages.text("wiki", "101", "1", "Ein Text über Betrieb.");
        enrichments(store, new FakeEmbeddingProvider("model-a@1", 4)).refresh(page);

        Enrichments upgraded = enrichments(store, new FakeEmbeddingProvider("model-b@1", 4));

        assertThat(upgraded.view(page)).extracting(view -> view.enrichment().processorId(),
                Enrichments.View::freshness).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("embedding", Enrichment.Freshness.STALE),
                        org.assertj.core.groups.Tuple.tuple("outline", Enrichment.Freshness.CURRENT));
        assertThat(upgraded.refresh(page)).isEqualTo(new Enrichments.Run(true, 1, 1, 0));
        assertThat(store.find(page.key()).get(0).payloadJson()).contains("\"model\":\"model-b@1\"");
    }

    @Test
    void anUnreachableModelIsAFailedResultNotAFailedSync() {
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        PageSnapshot page = EnrichmentTestPages.text("wiki", "101", "1", "Text");
        Enrichments enrichments = enrichments(store, provider("m", 2, texts -> {
            throw new EmbeddingUnavailableException("Zeitüberschreitung");
        }));

        assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 2, 0, 1));
        assertThat(store.find(page.key()).get(0).errorCode()).isEqualTo(Enrichments.PROCESSOR_ERROR);
    }

    private static Enrichments enrichments(InMemoryEnrichmentStore store, EmbeddingProvider provider) {
        return new Enrichments(snapshot -> Optional.of(new PageText("test/v1",
                new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of())),
                List.of(new EmbeddingProcessor(provider, 500),
                        new com.aresstack.spaceharvester.application.enrichment.OutlineProcessor()),
                store, CLOCK);
    }

    private static EmbeddingProvider provider(String model, int dimensions,
                                              java.util.function.Function<List<String>, List<float[]>> embed) {
        return new EmbeddingProvider() {
            @Override
            public String modelId() {
                return model;
            }

            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return embed.apply(texts);
            }
        };
    }
}
