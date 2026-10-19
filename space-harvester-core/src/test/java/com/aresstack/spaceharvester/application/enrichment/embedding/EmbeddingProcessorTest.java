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
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Embeddings als Anreicherung: Zerlegung, Ergebnisformat, Validierung und Invalidierung bei Modellwechsel. */
class EmbeddingProcessorTest {

    private static final TextSegmentation SEGMENTS = KnowledgeTextSegmentation.defaults();

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void embedsEachSectionAndRecordsWhereTheChunkCameFrom() {
        String text = "Vorwort\nZiele\nDrei Ziele.\nBetrieb\nNachts.";
        int ziele = text.indexOf("Ziele");
        int betrieb = text.indexOf("Betrieb");
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Ziele", 1, ziele, betrieb),
                new PageText.Section("Betrieb", 1, betrieb, text.length())));
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("fake-model@1", 4);

        EnrichmentOutput output = new EmbeddingProcessor(provider, 100, SEGMENTS).process(page);

        assertThat(provider.calls).containsExactly(List.of("Vorwort", "Ziele\nDrei Ziele.", "Betrieb\nNachts."));
        assertThat(output.payloadJson()).startsWith("{\"model\":\"fake-model@1\",\"dimensions\":4,\"chunks\":[")
                .contains("{\"charStart\":0,\"charEnd\":7,\"section\":null,\"vector\":[")
                .contains("{\"charStart\":" + ziele + ",\"charEnd\":" + (betrieb - 1) + ",\"section\":\"Ziele\"");
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    void budgetAndOverlapAreConfigurableAndPartOfTheVersion() {
        String text = "Erster Satz hier. Zweiter Satz hier. Dritter Satz hier. Vierter Satz hier.";
        EmbeddingProcessor small = new EmbeddingProcessor(new FakeEmbeddingProvider("m", 2), 1500,
                KnowledgeTextSegmentation.of(8, 0));

        List<EmbeddingProcessor.Chunk> chunks = small.chunks(new PageText("t/v1", text, List.of()));

        // Ohne Überlappung beginnt jedes Stück hinter dem vorigen.
        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).start()).isGreaterThanOrEqualTo(chunks.get(i - 1).end());
        }
        assertThat(small.version()).isNotEqualTo(new EmbeddingProcessor(new FakeEmbeddingProvider("m", 2), 1500,
                SEGMENTS).version());
        assertThatThrownBy(() -> KnowledgeTextSegmentation.of(7, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeTextSegmentation.of(350, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void longSectionsAreSplitAtLineOrWordBoundaries() {
        String text = ("wort ".repeat(30) + "\n").repeat(3);
        EmbeddingProcessor processor = new EmbeddingProcessor(new FakeEmbeddingProvider("m", 2), 200, SEGMENTS);

        List<EmbeddingProcessor.Chunk> chunks = processor.chunks(new PageText("t/v1", text, List.of()));

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.end() - chunk.start()).isLessThanOrEqualTo(200);
            assertThat(text.charAt(chunk.end())).isIn('\n', ' ');
        });
        assertThat(chunks.get(0).start()).isZero();
        assertThat(chunks.get(chunks.size() - 1).end()).isEqualTo(text.stripTrailing().length());
    }

    @Test
    void aSentenceOverTheTokenBudgetIsSplitAtWordBoundaries() {
        String text = "wort ".repeat(500).strip();
        List<EmbeddingProcessor.Chunk> chunks = new EmbeddingProcessor(new FakeEmbeddingProvider("m", 2), 5000,
                SEGMENTS).chunks(new PageText("t/v1", text, List.of()));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(text.substring(chunk.start(), chunk.end())).startsWith("wort").endsWith("wort");
            assertThat(chunk.input(text).split(" ")).hasSizeLessThanOrEqualTo(350);
        });
        assertThat(chunks.getLast().end()).isEqualTo(text.length());
    }

    @Test
    void sentencesArePackedUpToTheTokenBudgetWithOneSentenceOverlapAndTheHeadingInFront() {
        // 60 Sätze zu je 10 Tokens (9 Wörter und ein Punkt): je Stück höchstens 350 Tokens samt Überschrift.
        StringBuilder body = new StringBuilder("Betrieb\n");
        for (int i = 0; i < 60; i++) {
            body.append("Satz ").append(i).append(" sagt etwas über den nächtlichen Betrieb aus. ");
        }
        String text = body.toString().strip();
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Betrieb", 1, 0, text.length())));
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);
        EmbeddingProcessor processor = new EmbeddingProcessor(provider, 5000, SEGMENTS);

        List<EmbeddingProcessor.Chunk> chunks = processor.chunks(page);
        processor.process(page);

        assertThat(chunks).hasSizeGreaterThan(1);
        com.aresstack.enterpriseai.astu.knowledge.KnowledgeTokenCounter counter =
                com.aresstack.enterpriseai.astu.knowledge.KnowledgeTokenCounter.wordsAndSymbols();
        assertThat(provider.calls.getFirst()).allSatisfy(input ->
                assertThat(counter.count(input)).isLessThanOrEqualTo(350));
        // Das erste Stück beginnt mit der Überschrift selbst, die folgenden tragen sie davor.
        assertThat(provider.calls.getFirst().get(0)).startsWith("Betrieb\nSatz 0 ");
        assertThat(provider.calls.getFirst().get(1)).startsWith("Betrieb\n\nSatz ");
        for (int i = 1; i < chunks.size(); i++) {
            // Ein Satz Überlappung: das nächste Stück beginnt vor dem Ende des vorigen, an einer Satzgrenze.
            assertThat(chunks.get(i).start()).isLessThan(chunks.get(i - 1).end());
            assertThat(text.substring(chunks.get(i).start())).startsWith("Satz ");
        }
        assertThat(chunks.getLast().end()).isEqualTo(text.length());
    }

    @Test
    void textWithoutSpacesIsCutWithinTheTokenBudgetToo() {
        String text = "(){}[];".repeat(300);
        List<EmbeddingProcessor.Chunk> chunks = new EmbeddingProcessor(new FakeEmbeddingProvider("m", 2), 1500,
                SEGMENTS).chunks(new PageText("t/v1", text, List.of()));

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk ->
                assertThat(SEGMENTS.tokens(chunk.input(text))).isLessThanOrEqualTo(SEGMENTS.maxTokens()));
        assertThat(chunks.getLast().end()).isEqualTo(text.length());
    }

    @Test
    void nestedSectionsCarryTheirHeadingPath() {
        String text = "Handbuch\nEinleitung.\nBetrieb\n" + "Ein Satz über den Betrieb. ".repeat(40).strip();
        int betrieb = text.indexOf("Betrieb\n");
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Handbuch", 1, 0, betrieb),
                new PageText.Section("Betrieb", 2, betrieb, text.length())));
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);

        EnrichmentOutput output = new EmbeddingProcessor(provider, 600, SEGMENTS).process(page);

        assertThat(provider.calls.getFirst()).anySatisfy(input ->
                assertThat(input).startsWith("Handbuch > Betrieb\n\nEin Satz"));
        // Das erste Stück beginnt mit der eigenen Überschrift; davor stehen die übergeordneten.
        assertThat(provider.calls.getFirst()).anySatisfy(input ->
                assertThat(input).startsWith("Handbuch\n\nBetrieb\nEin Satz"));
        // Die Kette steht auch im Ergebnis, damit verwandte Abschnitte denselben Abschnitt erkennen.
        assertThat(output.payloadJson()).contains("\"section\":\"Handbuch\"")
                .contains("\"section\":\"Handbuch > Betrieb\"");
    }

    @Test
    void anEmptyPageCallsNoModel() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);

        assertThat(new EmbeddingProcessor(provider, 100, SEGMENTS).process(new PageText("t/v1", " \n", List.of()))
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
            assertThatThrownBy(() -> new EmbeddingProcessor(provider, 100, SEGMENTS).process(page))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void invalidModelsAreRefusedUpFront() {
        assertThatThrownBy(() -> new EmbeddingProcessor(new FakeEmbeddingProvider("mit leerzeichen", 4), 100, SEGMENTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProcessor(new FakeEmbeddingProvider("m", 0), 100, SEGMENTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProcessor(new FakeEmbeddingProvider("m", 4), 10, SEGMENTS))
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

    @Test
    void aLongQueryIsShortenedAtAWordBoundaryToTheChunkBudgetBeforeItIsEmbedded() {
        // 40 Sätze zu je 10 Tokens: über dem Standardbudget von 350 Tokens.
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            query.append("Satz ").append(i).append(" fragt etwas über den nächtlichen Betrieb. ");
        }
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);
        EmbeddingProcessor processor = new EmbeddingProcessor(provider, 1500, SEGMENTS);

        processor.current().orElseThrow().embedQuery(query.toString());

        String embedded = provider.calls.getLast().getFirst();
        assertThat(query.toString()).startsWith(embedded);
        assertThat(SEGMENTS.tokens(embedded)).isLessThanOrEqualTo(350).isGreaterThan(340);

        // Die Grenze ist das eingestellte Budget je Stück (embedding-chunk-tokens), gekürzt wie im Client an einer
        // Wortgrenze, auch mitten im zweiten Satz.
        FakeEmbeddingProvider small = new FakeEmbeddingProvider("m", 2);
        new EmbeddingProcessor(small, 1500, KnowledgeTextSegmentation.of(10, 1))
                .embedQuery("Der Drucker im zweiten Stock druckt nicht. Er zeigt einen Papierstau an.");
        assertThat(small.calls).containsExactly(List.of("Der Drucker im zweiten Stock druckt nicht. Er zeigt"));
    }

    @Test
    void aShortQueryIsEmbeddedUnchanged() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);

        new EmbeddingProcessor(provider, 1500, SEGMENTS).embedQuery("  Wer druckt nachts?  ");

        assertThat(provider.calls).containsExactly(List.of("  Wer druckt nachts?  "));
    }

    @Test
    void aSingleOverlongSentenceIsShortenedAtAWordBoundary() {
        String query = "wort ".repeat(400) + "Ende.";
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);

        new EmbeddingProcessor(provider, 1500, SEGMENTS).embedQuery(query);

        assertThat(provider.calls).containsExactly(List.of("wort ".repeat(350).strip()));
    }

    @Test
    void aSingleOverlongWordIsCutToOneTokenPerCharacter() {
        String query = "(){}".repeat(200);
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider("m", 2);

        new EmbeddingProcessor(provider, 1500, SEGMENTS).embedQuery(query);

        assertThat(provider.calls).containsExactly(List.of(query.substring(0, 350)));
    }

    private static Enrichments enrichments(InMemoryEnrichmentStore store, EmbeddingProvider provider) {
        return new Enrichments(snapshot -> Optional.of(new PageText("test/v1",
                new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of())),
                List.of(new EmbeddingProcessor(provider, 500, SEGMENTS),
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
