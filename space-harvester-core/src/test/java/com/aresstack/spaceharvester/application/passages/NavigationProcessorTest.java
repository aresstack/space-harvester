package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Prozessor „navigation“ und Lesen für Reader und REST (#181, ADR 0020). */
class NavigationProcessorTest {

    private static final PageKey KEY = new PageKey("wiki", "101");

    private final PageText text = new DocumentNavigationBuilderTest.Page().heading(2, "Verarbeitung")
            .paragraphs(DocumentNavigationBuilderTest.PASSAGES).heading(2, "Ende").paragraphs("Schluss.").text();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<EmbeddingProvider> model = new AtomicReference<>(new EmbeddingProvider() {
        @Override
        public String modelId() {
            return "vocabulary@1";
        }

        @Override
        public int dimensions() {
            return 6;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.incrementAndGet();
            return texts.stream().map(DocumentNavigationBuilderTest.VOCABULARY::vector).toList();
        }
    });
    private final AtomicBoolean approved = new AtomicBoolean(true);
    private final EmbeddingProcessor embedding = new EmbeddingProcessor(model::get, 1000,
            KnowledgeTextSegmentation.defaults(), sourceId -> approved.get())
            .withPassages(new PassageChunks(new PassageSegmentation(
                    SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()),
                    KnowledgeTextSegmentation.defaults())));
    private final List<Enrichment> stored = new ArrayList<>();
    private final DocumentNavigationBuilder builder = new DocumentNavigationBuilder();
    private final NavigationProcessor processor = new NavigationProcessor(builder, embedding, key -> stored);

    @Test
    void withCurrentPassageEmbeddingsTopicSectionsAppearUnderTheirHeading() {
        storeEmbeddings();
        int before = calls.get();

        DocumentNavigationView view = read(processor.process(KEY, text));

        assertThat(calls.get()).as("eingebettet wird hier nichts").isEqualTo(before);
        assertThat(view.state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(view.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.SEMANTIC);
        assertThat(view.reason()).isNull();
        assertThat(view.chapters()).extracting(NavigationSection::label).containsExactly("Verarbeitung", "Ende");
        assertThat(view.chapters().getFirst().children()).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.origin()).isEqualTo(NavigationSection.Origin.TOPIC));
    }

    @Test
    void withoutEmbeddingsForThisTextTheHeadingsStayAndItIsRetried() {
        DocumentNavigationView view = read(processor.process(KEY, text));

        assertThat(view.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.HEADINGS);
        assertThat(view.reason()).isEqualTo(NavigationPayload.REASON_NOT_EMBEDDED);
        assertThat(view.state()).isEqualTo(PagePassages.State.STALE);
        assertThat(view.chapters()).extracting(NavigationSection::label).containsExactly("Verarbeitung", "Ende");
    }

    @Test
    void sourceNotApprovedNeverReachesTheModel() {
        storeEmbeddings();
        approved.set(false);
        int before = calls.get();

        DocumentNavigationView view = read(processor.process(KEY, text));

        assertThat(calls.get()).isEqualTo(before);
        assertThat(view.reason()).isEqualTo(NavigationPayload.REASON_NOT_APPROVED);
        assertThat(view.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.HEADINGS);
    }

    @Test
    void withoutModelTheHeadingsAreCurrentUntilAModelIsSet() {
        model.set(null);
        EnrichmentOutput output = processor.process(KEY, text);

        assertThat(read(output).reason()).isEqualTo(NavigationPayload.REASON_NO_MODEL);
        assertThat(read(output).state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(processor.version()).endsWith("no-model");
    }

    @Test
    void neverShowsTheNavigationOfAnotherTextButItsHeadingsAtOnce() {
        storeEmbeddings();
        Enrichment result = enrichment(processor.process(KEY, text));
        PageText changed = new DocumentNavigationBuilderTest.Page().heading(2, "Neu").paragraphs("Text.").text();

        DocumentNavigationView view = DocumentNavigationView.read(KEY,
                List.of(new Enrichments.View(result, Enrichment.Freshness.STALE)), changed, builder);

        assertThat(view.state()).isEqualTo(PagePassages.State.STALE);
        assertThat(view.chapters()).extracting(NavigationSection::label).containsExactly("Neu");
        assertThat(DocumentNavigationView.read(KEY, List.of(), text, builder).state())
                .isEqualTo(PagePassages.State.MISSING);
    }

    private void storeEmbeddings() {
        EnrichmentOutput vectors = embedding.process(text);
        stored.add(new Enrichment(KEY, embedding.id(), vectors.version() != null ? vectors.version()
                : embedding.version(), text.inputSha256(), "1", Enrichment.Status.OK, vectors.payloadJson(),
                vectors.warnings(), null, Instant.EPOCH));
    }

    private DocumentNavigationView read(EnrichmentOutput output) {
        Enrichment result = enrichment(output);
        return DocumentNavigationView.read(KEY, List.of(new Enrichments.View(result,
                result.freshness(text.inputSha256(), processor.version()))), text, builder);
    }

    private Enrichment enrichment(EnrichmentOutput output) {
        return new Enrichment(KEY, NavigationProcessor.ID, output.version() != null ? output.version()
                : processor.version(), text.inputSha256(), "1", Enrichment.Status.OK, output.payloadJson(),
                output.warnings(), null, Instant.EPOCH);
    }
}
