package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Prozessor „passages“ und Lesen für die Leseansicht und REST (#181, ADR 0020). */
class PassageProcessorTest {

    static final PageKey KEY = new PageKey("wiki", "101");
    static final PageText TEXT = new PageText(PassageSegmentationTest.CONFLUENCE, PassageSegmentationTest.TOPICS,
            List.of());

    private final AtomicReference<EmbeddingProvider> model = new AtomicReference<>(new TopicModel());
    private final AtomicBoolean approved = new AtomicBoolean(true);
    private final EmbeddingProcessor embedding = new EmbeddingProcessor(model::get, 1000,
            KnowledgeTextSegmentation.defaults(), sourceId -> approved.get());
    private final PassageProcessor processor = new PassageProcessor(
            SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()), KnowledgeTextSegmentation.defaults(),
            embedding);

    /** Ein Modell, das Sätze nach Thema einbettet. */
    static class TopicModel implements EmbeddingProvider {

        boolean unavailable;

        @Override
        public String modelId() {
            return "topics@1";
        }

        @Override
        public int dimensions() {
            return 3;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            if (unavailable) {
                throw new EmbeddingUnavailableException("weg");
            }
            return texts.stream().map(PassageSegmentationTest.TopicEmbedder::vector).toList();
        }
    }

    @Test
    void approvedSourceWithModelGetsSemanticPassagesWithTheirWording() {
        PagePassages passages = read(processor.process(KEY, TEXT));

        assertThat(passages.state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(passages.mode()).isEqualTo(PassagePayload.Mode.SEMANTIC);
        assertThat(passages.model()).isEqualTo("topics@1");
        assertThat(passages.reason()).isNull();
        assertThat(passages.passages()).extracting(PagePassages.Passage::index, PagePassages.Passage::title,
                PagePassages.Passage::boundary).containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, "Die Katze schläft im Korb",
                        PassageSegmentation.Boundary.STRUCTURE),
                org.assertj.core.groups.Tuple.tuple(1, "Der Server startet um acht",
                        PassageSegmentation.Boundary.TOPIC));
        assertThat(passages.passages().get(1).text()).startsWith("Der Server").endsWith("stoppt nachts.");
        assertThat(processor.version()).contains("model=topics@1").contains("sentences=splitter");
        assertThat(processor.background()).isTrue();
    }

    @Test
    void sourceNotApprovedStaysOffTheNetworkAndIsSplitByStructure() {
        approved.set(false);
        TopicModel spy = new TopicModel();
        spy.unavailable = true; // jeder Aufruf schlüge fehl
        model.set(spy);

        PagePassages passages = read(processor.process(KEY, TEXT));

        assertThat(passages.state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(passages.mode()).isEqualTo(PassagePayload.Mode.STRUCTURE);
        assertThat(passages.reason()).isEqualTo(PassagePayload.REASON_NOT_APPROVED);
        assertThat(passages.passages()).hasSize(1);
    }

    @Test
    void withoutModelTheFallbackIsCurrentUntilAModelIsSet() {
        model.set(null);
        EnrichmentOutput output = processor.process(KEY, TEXT);

        assertThat(read(output).reason()).isEqualTo(PassagePayload.REASON_NO_MODEL);
        assertThat(read(output).state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(processor.background()).isFalse();

        model.set(new TopicModel());
        PagePassages afterModel = read(output);
        assertThat(afterModel.state()).isEqualTo(PagePassages.State.STALE);
        assertThat(afterModel.passages()).hasSize(1); // gehört noch zu diesem Text
    }

    @Test
    void unreachableModelFallsBackAndIsRetried() {
        TopicModel down = new TopicModel();
        down.unavailable = true;
        model.set(down);

        PagePassages passages = read(processor.process(KEY, TEXT));

        assertThat(passages.reason()).isEqualTo(PassagePayload.REASON_MODEL_UNAVAILABLE);
        assertThat(passages.state()).isEqualTo(PagePassages.State.STALE);
        assertThat(passages.warnings()).isNotEmpty();
        assertThat(passages.passages()).hasSize(1);
    }

    @Test
    void neverShowsPassagesOfAnotherTextOrAFailedRun() {
        Enrichment stored = enrichment(processor.process(KEY, TEXT));
        PageText changed = new PageText(PassageSegmentationTest.CONFLUENCE, "Ganz neuer Text.", List.of());
        Enrichment failed = new Enrichment(KEY, PassageProcessor.ID, processor.version(), TEXT.inputSha256(), "1",
                Enrichment.Status.FAILED, "{}", List.of(), Enrichments.PROCESSOR_ERROR, Instant.EPOCH);

        assertThat(PagePassages.read(KEY, List.of(new Enrichments.View(stored, Enrichment.Freshness.STALE)), changed))
                .satisfies(found -> {
                    assertThat(found.state()).isEqualTo(PagePassages.State.STALE);
                    assertThat(found.passages()).isEmpty();
                });
        assertThat(PagePassages.read(KEY, List.of(new Enrichments.View(failed, Enrichment.Freshness.CURRENT)), TEXT)
                .state()).isEqualTo(PagePassages.State.FAILED);
        assertThat(PagePassages.read(KEY, List.of(), TEXT).state()).isEqualTo(PagePassages.State.MISSING);
        assertThat(PagePassages.read(new PageKey("wiki", "other"),
                List.of(new Enrichments.View(stored, Enrichment.Freshness.CURRENT)), TEXT).state())
                .isEqualTo(PagePassages.State.MISSING);
    }

    @Test
    void payloadStoresPositionsButNoText() {
        String json = processor.process(KEY, TEXT).payloadJson();

        assertThat(json).doesNotContain("Katze").contains("\"mode\":\"semantic\"").contains("\"boundary\":\"topic\"");
    }

    /** Wie {@link Enrichments#view}: aktuell, wenn die Version zur jetzigen des Prozessors passt. */
    private PagePassages read(EnrichmentOutput output) {
        Enrichment stored = enrichment(output);
        return PagePassages.read(KEY, List.of(new Enrichments.View(stored,
                stored.freshness(TEXT.inputSha256(), processor.version()))), TEXT);
    }

    private Enrichment enrichment(EnrichmentOutput output) {
        return new Enrichment(KEY, PassageProcessor.ID, output.version() != null ? output.version()
                : processor.version(), TEXT.inputSha256(), "1", Enrichment.Status.OK, output.payloadJson(),
                output.warnings(), null, Instant.EPOCH);
    }
}
