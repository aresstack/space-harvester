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
    void everyPassageHasAStableIdThatChangesWithTextOrModel() {
        EnrichmentOutput output = processor.process(KEY, TEXT);
        PagePassages passages = read(output);

        assertThat(passages.passages()).extracting(PagePassages.Passage::id).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(PassageId.looksLike(id)).isTrue());
        PassageId first = PassageId.parse(passages.passages().get(1).id());
        assertThat(first.sourceId()).isEqualTo("wiki");
        assertThat(first.pageId()).isEqualTo("101");
        assertThat(first.position()).isEqualTo(1);
        assertThat(first.capture()).isEqualTo(PassageId.captureOf(PassagePayload.textSha256(TEXT.text())));
        assertThat(first.embedding()).isEqualTo(PassageId.versionTag("topics@1"));
        assertThat(read(output).passages()).extracting(PagePassages.Passage::id)
                .isEqualTo(passages.passages().stream().map(PagePassages.Passage::id).toList());

        approved.set(false);
        PagePassages structure = read(processor.process(KEY, TEXT));
        assertThat(PassageId.parse(structure.passages().get(0).id()).embedding())
                .isEqualTo(PassageId.versionTag(PassageId.NO_MODEL));
        assertThat(PassageId.parse(structure.passages().get(0).id()).capture()).isEqualTo(first.capture());
    }

    @Test
    void aPassageIdIsFoundWithItsNeighboursOrTellsWhyNot() {
        PagePassages current = read(processor.process(KEY, TEXT));
        List<PagePassages.Passage> all = current.passages();
        assertThat(all).hasSize(2);

        PassageLookup second = PassageLookup.of(PassageId.parse(all.get(1).id()), current, 1);
        assertThat(second.match()).isEqualTo(PassageLookup.Match.FOUND);
        assertThat(second.passages()).containsExactly(all.get(0), all.get(1));
        assertThat(PassageLookup.of(PassageId.parse(all.get(1).id()), current, 0).passages())
                .containsExactly(all.get(1));

        PassageId found = PassageId.parse(all.get(1).id());
        PassageId olderText = PassageId.of("wiki", "101", "0".repeat(64), 1, found.segmentation(), found.embedding());
        assertThat(PassageLookup.of(olderText, current, 1)).satisfies(lookup -> {
            assertThat(lookup.match()).isEqualTo(PassageLookup.Match.OLDER_VERSION);
            assertThat(lookup.passages()).isEqualTo(all);
        });
        PassageId otherModel = PassageId.of("wiki", "101", found.capture(), 1, found.segmentation(), "00000000");
        assertThat(PassageLookup.of(otherModel, current, 1).match()).isEqualTo(PassageLookup.Match.CHANGED);
        assertThat(PassageLookup.of(found, PagePassages.read(KEY, List.of(), TEXT), 1).match())
                .isEqualTo(PassageLookup.Match.UNAVAILABLE);
    }

    @Test
    void theIdOfAHitsRangeIsTheIdTheReadingViewShowsAndNoneForABrokenResult() {
        Enrichment stored = enrichment(processor.process(KEY, TEXT));
        for (PagePassages.Passage passage : read(processor.process(KEY, TEXT)).passages()) {
            assertThat(PagePassages.idAt(KEY, stored, TEXT.inputSha256(), passage.start(), passage.end()))
                    .isEqualTo(passage.id());
        }
        // Ein Absatz hinter dem Textende wird nie gezeigt; die übrigen behalten ihre Stelle im Ergebnis.
        String broken = stored.payloadJson().replaceFirst("\"end\":\\d+", "\"end\":999999");
        Enrichment damaged = new Enrichment(KEY, PassageProcessor.ID, stored.processorVersion(), stored.inputSha256(),
                "1", Enrichment.Status.OK, broken, List.of(), null, Instant.EPOCH);
        List<PagePassages.Passage> shown = PagePassages.read(KEY, List.of(new Enrichments.View(damaged,
                Enrichment.Freshness.CURRENT)), TEXT).passages();

        assertThat(shown).singleElement().satisfies(passage -> {
            assertThat(passage.index()).isEqualTo(1);
            assertThat(PassageId.parse(passage.id()).position()).isEqualTo(1);
            // Ohne Text ist der kaputte Absatz nur an der Überlappung zu erkennen: dann keine ID statt einer, die auf
            // ihn zeigt.
            assertThat(PagePassages.idAt(KEY, damaged, TEXT.inputSha256(), passage.start(), passage.end())).isNull();
        });
        assertThat(PagePassages.idAt(KEY, stored, "sha256:" + "0".repeat(64), shown.getFirst().start(),
                shown.getFirst().end())).as("anderer Text").isNull();
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
