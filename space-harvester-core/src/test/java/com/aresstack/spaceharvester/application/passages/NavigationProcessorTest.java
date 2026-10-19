package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
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
    /** Satzgrenzen wie ein Satzmodell; ohne Modell nur Überschriften (no-sentence-model). */
    private static final SentenceSegmenter SENTENCE_MODEL = new SentenceSegmenter() {
        @Override
        public List<String> sentences(String paragraph) {
            return SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()).sentences(paragraph);
        }

        @Override
        public String id() {
            return "test-model";
        }
    };
    private final EmbeddingProcessor embedding = embedding(SENTENCE_MODEL);
    private final List<Enrichment> stored = new ArrayList<>();
    private final DocumentNavigationBuilder builder = new DocumentNavigationBuilder();
    private final NavigationProcessor processor = new NavigationProcessor(builder, embedding, key -> stored);

    @Test
    void withCurrentPassageEmbeddingsTopicSectionsAppearUnderTheirHeading() {
        storeEmbeddings();
        int before = calls.get();

        DocumentNavigationView view = read(processor.process(KEY, text));

        assertThat(calls.get()).as("eingebettet wird hier nichts").isEqualTo(before);
        assertThat(processor.background()).as("nach den Embeddings in derselben Warteschlange").isTrue();
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
        // Gilt bis Text, Modell oder Verfahren wechseln; kein Platz in der Warteschlange.
        assertThat(view.state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(processor.background(KEY)).isFalse();
    }

    @Test
    void withoutModelTheHeadingsAreCurrentUntilAModelIsSet() {
        model.set(null);
        EnrichmentOutput output = processor.process(KEY, text);

        assertThat(read(output).reason()).isEqualTo(NavigationPayload.REASON_NO_MODEL);
        assertThat(read(output).state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(processor.version()).endsWith("no-model");
        assertThat(processor.background()).isFalse();
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

    /** Ein Benenner, der zählt, was ihn erreicht. */
    private class Labeler implements NavigationLabelModel {

        final List<List<String>> calls = new ArrayList<>();
        String model = "gpt-x";
        boolean down;
        boolean approvedSource = true;

        @Override
        public String version() {
            return "test:" + model;
        }

        @Override
        public boolean available() {
            return model != null;
        }

        @Override
        public boolean mayLabel(String sourceId) {
            return approvedSource;
        }

        @Override
        public Labels labels(String documentTitle, List<String> sectionTexts) {
            calls.add(sectionTexts);
            if (down) {
                return new Labels(java.util.Collections.nCopies(sectionTexts.size(), null), false);
            }
            List<String> names = new ArrayList<>();
            for (int i = 0; i < sectionTexts.size(); i++) {
                names.add("Thema " + (i + 1));
            }
            return new Labels(names, true);
        }
    }

    @Test
    void theChatModelNamesTheTopicSectionsAndItsChangeRecomputes() {
        storeEmbeddings();
        Labeler labeler = new Labeler();
        NavigationProcessor named = new NavigationProcessor(builder, embedding, key -> stored, labeler);

        EnrichmentOutput output = named.process(KEY, text);
        DocumentNavigationView view = read(output, named);

        assertThat(view.state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(view.chapters().getFirst().children().getFirst().label()).isEqualTo("Thema 1");
        assertThat(labeler.calls).hasSize(1);
        String before = named.version();
        labeler.model = "gpt-y";
        assertThat(named.version()).isNotEqualTo(before);
        assertThat(read(output, named).state()).isEqualTo(PagePassages.State.STALE);
    }

    @Test
    void anUnreachableChatModelKeepsTheAnalysisLabelsAndIsRetried() {
        storeEmbeddings();
        Labeler labeler = new Labeler();
        labeler.down = true;
        NavigationProcessor named = new NavigationProcessor(builder, embedding, key -> stored, labeler);

        DocumentNavigationView view = read(named.process(KEY, text), named);

        assertThat(view.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.SEMANTIC);
        assertThat(view.reason()).isEqualTo(NavigationPayload.REASON_MODEL_UNAVAILABLE);
        assertThat(view.state()).isEqualTo(PagePassages.State.STALE);
        assertThat(view.chapters().getFirst().children().getFirst().label()).doesNotStartWith("Thema");
    }

    @Test
    void noTextOfAnUnapprovedSourceReachesTheChatModel() {
        storeEmbeddings();
        Labeler labeler = new Labeler();
        labeler.approvedSource = false;
        NavigationProcessor named = new NavigationProcessor(builder, embedding, key -> stored, labeler);

        DocumentNavigationView view = read(named.process(KEY, text), named);

        assertThat(labeler.calls).isEmpty();
        assertThat(view.chapters().getFirst().children().getFirst().label()).doesNotStartWith("Thema");
    }

    @Test
    void withoutSentenceModelOnlyTheHeadingsAppearLikeInTheClient() {
        EmbeddingProcessor splitter = embedding(SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()));
        NavigationProcessor bySplitter = new NavigationProcessor(builder, splitter, key -> stored);

        DocumentNavigationView view = read(bySplitter.process(KEY, text), bySplitter);

        assertThat(view.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.HEADINGS);
        assertThat(view.reason()).isEqualTo(NavigationPayload.REASON_NO_SENTENCE_MODEL);
        assertThat(view.state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(bySplitter.background(KEY)).isFalse();
    }

    @Test
    void failedEmbeddingsAreTheirOwnReasonAndStayUntilTextOrModelChange() {
        stored.add(new Enrichment(KEY, embedding.id(), embedding.version(), text.inputSha256(), "1",
                Enrichment.Status.FAILED, "{}", List.of(), Enrichments.PROCESSOR_ERROR, Instant.EPOCH));

        DocumentNavigationView view = read(processor.process(KEY, text));

        assertThat(view.reason()).isEqualTo(NavigationPayload.REASON_INDEXING_FAILED);
        assertThat(view.analysis()).isEqualTo(DocumentNavigationBuilder.Analysis.HEADINGS);
        // Ein gescheitertes Embedding wird nicht von selbst neu berechnet; jeder Abruf rechnete sonst umsonst neu.
        assertThat(view.state()).isEqualTo(PagePassages.State.CURRENT);
    }

    @Test
    void theTitleOfThePageReachesTheChatModel() {
        storeEmbeddings();
        List<String> titles = new ArrayList<>();
        Labeler labeler = new Labeler() {
            @Override
            public Labels labels(String documentTitle, List<String> sectionTexts) {
                titles.add(documentTitle);
                return super.labels(documentTitle, sectionTexts);
            }
        };
        NavigationProcessor named = new NavigationProcessor(builder, embedding, key -> stored, labeler);
        PageSnapshot snapshot = PageSnapshot.of("wiki", new PageMetadata("101", "wiki:101", "Reisekosten", "DEV", null,
                "1", Instant.EPOCH, null, null), new NativePayload(new byte[0], "text/plain", "test"), Instant.EPOCH);

        named.process(snapshot, text);

        assertThat(titles).containsExactly("Reisekosten");
    }

    @Test
    void theViewNamesTheTextItBelongsToAndFindsEverySection() {
        storeEmbeddings();
        Enrichment result = enrichment(processor.process(KEY, text));

        DocumentNavigationView view = DocumentNavigationView.read(KEY, "Reisekosten",
                List.of(new Enrichments.View(result, Enrichment.Freshness.CURRENT)), text, builder);

        assertThat(view.title()).isEqualTo("Reisekosten");
        assertThat(view.inputSha256()).isEqualTo(text.inputSha256());
        assertThat(view.textSha256()).isEqualTo(PassagePayload.textSha256(text.text()));
        List<NavigationSection> all = view.flatten();
        assertThat(all).hasSizeGreaterThan(view.chapters().size());
        assertThat(all.getFirst()).isEqualTo(view.chapters().getFirst());
        assertThat(all.get(1)).isEqualTo(view.chapters().getFirst().children().getFirst());
        NavigationSection topic = view.chapters().getFirst().children().getFirst();
        assertThat(view.find(topic.id())).isEqualTo(topic);
        assertThat(view.find("gibt-es-nicht")).isNull();
        assertThat(DocumentNavigationView.read(KEY, "Reisekosten", List.of(), null, builder).inputSha256()).isNull();
    }

    private EmbeddingProcessor embedding(SentenceSegmenter sentences) {
        return new EmbeddingProcessor(model::get, 1000, KnowledgeTextSegmentation.defaults(),
                sourceId -> approved.get()).withPassages(new PassageChunks(new PassageSegmentation(sentences,
                KnowledgeTextSegmentation.defaults())));
    }

    private void storeEmbeddings() {
        EnrichmentOutput vectors = embedding.process(text);
        stored.add(new Enrichment(KEY, embedding.id(), vectors.version() != null ? vectors.version()
                : embedding.version(), text.inputSha256(), "1", Enrichment.Status.OK, vectors.payloadJson(),
                vectors.warnings(), null, Instant.EPOCH));
    }

    private DocumentNavigationView read(EnrichmentOutput output) {
        return read(output, processor);
    }

    private DocumentNavigationView read(EnrichmentOutput output, NavigationProcessor by) {
        Enrichment result = new Enrichment(KEY, NavigationProcessor.ID, output.version() != null ? output.version()
                : by.version(), text.inputSha256(), "1", Enrichment.Status.OK, output.payloadJson(),
                output.warnings(), null, Instant.EPOCH);
        return DocumentNavigationView.read(KEY, List.of(new Enrichments.View(result,
                result.freshness(text.inputSha256(), by.version()))), text, builder);
    }

    private Enrichment enrichment(EnrichmentOutput output) {
        return new Enrichment(KEY, NavigationProcessor.ID, output.version() != null ? output.version()
                : processor.version(), text.inputSha256(), "1", Enrichment.Status.OK, output.payloadJson(),
                output.warnings(), null, Instant.EPOCH);
    }
}
