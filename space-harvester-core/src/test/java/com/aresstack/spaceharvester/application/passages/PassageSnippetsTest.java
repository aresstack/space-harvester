package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Suchschnipsel aus semantischen Absätzen (#181, ADR 0020). */
class PassageSnippetsTest {

    private static final PageKey KEY = PassageProcessorTest.KEY;
    private static final String WINDOW = "Die Katze schläft im Korb. Die Katze frisst am Abend.";

    private final FakeIndex index = new FakeIndex(PassageProcessorTest.TEXT.text());
    private final List<Enrichment> stored = new ArrayList<>();
    private final PassageSnippets snippets = new PassageSnippets(index, key -> stored);

    @Test
    void snippetIsThePassageWithMostQueryWords() {
        stored.add(semantic(PassageProcessorTest.TEXT));

        List<PageTextIndex.Match> matches = snippets.search("server nachts", Set.of("wiki"), null, 10);

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.snippet()).isEqualTo(
                    "Der Server startet um acht. Der Server läuft stabil. Der Server stoppt nachts.");
            assertThat(match.title()).isEqualTo("Seite");
        });
    }

    @Test
    void theSnippetNamesThePassageIdOfItsPassageLikeTheReadingView() {
        stored.add(semantic(PassageProcessorTest.TEXT));
        List<PagePassages.Passage> passages = PagePassages.read(KEY, List.of(new Enrichments.View(stored.getFirst(),
                Enrichment.Freshness.CURRENT)), PassageProcessorTest.TEXT).passages();

        assertThat(snippets.search("server nachts", Set.of("wiki"), null, 10)).singleElement()
                .satisfies(match -> assertThat(match.passageId()).isEqualTo(passages.get(1).id()));
        assertThat(snippets.search("titel", Set.of("wiki"), null, 10).getFirst().passageId())
                .as("das Fenster des Index stammt aus keinem Absatz").isNull();
    }

    @Test
    void anAttachmentHitKeepsItsExcerptAndTheWrapperPassesAttachmentsOn() {
        stored.add(semantic(PassageProcessorTest.TEXT));
        index.attachmentId = "att-1";

        // Absätze gibt es nur für Seiten: der Auszug aus dem Anhang (#183) bleibt, der Treffer bleibt ein Anhangstreffer.
        assertThat(snippets.search("server", Set.of("wiki"), null, 10)).singleElement().satisfies(match -> {
            assertThat(match.inAttachment()).isTrue();
            assertThat(match.attachmentTitle()).isEqualTo("plan.pdf");
            assertThat(match.snippet()).isEqualTo(WINDOW);
        });

        PageTextIndex.AttachmentEntry entry = new PageTextIndex.AttachmentEntry(KEY, "S", "att-1", "plan.pdf", "Text",
                "sha256:a");
        snippets.putAttachment(entry);
        assertThat(index.attachments).containsExactly(entry);
        snippets.removeAttachment(KEY, "att-1");
        assertThat(index.attachments).isEmpty();
    }

    @Test
    void aPageHitWithPassagesStaysAPageHit() {
        stored.add(semantic(PassageProcessorTest.TEXT));

        assertThat(snippets.search("server nachts", Set.of("wiki"), null, 10)).singleElement()
                .satisfies(match -> assertThat(match.inAttachment()).isFalse());
    }

    @Test
    void keepsTheWindowWithoutPassagesForThisText() {
        assertThat(snippets.search("server", Set.of("wiki"), null, 10).getFirst().snippet()).isEqualTo(WINDOW);

        // Absätze zu einem älteren Text: Stellen passen nicht mehr.
        stored.add(semantic(new PageText(PassageSegmentationTest.CONFLUENCE, "Der Server ist alt. Sonst nichts.",
                List.of())));
        assertThat(snippets.search("server", Set.of("wiki"), null, 10).getFirst().snippet()).isEqualTo(WINDOW);
    }

    @Test
    void keepsTheWindowWhenNoPassageHasAQueryWordOrTheSnippetIsWithheld() {
        stored.add(semantic(PassageProcessorTest.TEXT));

        assertThat(snippets.search("titel", Set.of("wiki"), null, 10).getFirst().snippet()).isEqualTo(WINDOW);

        index.window = "";
        assertThat(snippets.search("server", Set.of("wiki"), null, 10).getFirst().snippet()).isEmpty();
    }

    @Test
    void storedPayloadWithoutTextHashKeepsTheWindow() {
        stored.add(new Enrichment(KEY, PassageProcessor.ID, "1", "sha256:x", "1", Enrichment.Status.OK,
                "{\"mode\":\"structure\",\"segmentation\":\"s\",\"sentences\":1,\"passages\":[{\"start\":0,\"end\":5,"
                        + "\"firstSentence\":0,\"sentences\":1,\"kind\":\"paragraph\",\"boundary\":\"structure\"}]}",
                List.of(), null, Instant.EPOCH));

        assertThat(snippets.search("katze", Set.of("wiki"), null, 10).getFirst().snippet()).isEqualTo(WINDOW);
    }

    @Test
    void longPassageIsCutAroundTheFirstQueryWordAtWordBoundaries() {
        String text = "Wort ".repeat(200) + "Treffer hier " + "Wort ".repeat(200);
        PassageSegmentation.Passage passage = new PassageSegmentation.Passage(0, text.length(), 0, 1, null,
                PassageSegmentation.Kind.PARAGRAPH, PassageSegmentation.Boundary.STRUCTURE, null);

        String excerpt = PassageSnippets.best(text, List.of(passage), Set.of("treffer")).orElseThrow();

        assertThat(excerpt).startsWith("… Wort").endsWith("Wort …").contains("Treffer hier");
        assertThat(excerpt.length()).isLessThanOrEqualTo(PassageSnippets.MAX_CHARS + 8);
    }

    private static Enrichment semantic(PageText text) {
        PassageProcessor processor = new PassageProcessor(
                SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()),
                KnowledgeTextSegmentation.defaults(), new EmbeddingProcessor(PassageProcessorTest.TopicModel::new,
                1000, KnowledgeTextSegmentation.defaults(), sourceId -> true));
        EnrichmentOutput output = processor.process(KEY, text);
        return new Enrichment(KEY, PassageProcessor.ID, processor.version(), text.inputSha256(), "1",
                Enrichment.Status.OK, output.payloadJson(), output.warnings(), null, Instant.EPOCH);
    }

    /** Ein Index mit einer Seite und festem Fenster. */
    private static final class FakeIndex implements PageTextIndex {

        private final String text;
        String window = WINDOW;
        String attachmentId;
        final List<AttachmentEntry> attachments = new ArrayList<>();

        FakeIndex(String text) {
            this.text = text;
        }

        @Override
        public List<Match> search(String query, Set<String> sourceIds, SearchSpaces spaces, int limit) {
            return List.of(new Match(KEY.sourceId(), KEY.pageId(), "S", "Seite", window, attachmentId,
                    attachmentId == null ? null : "plan.pdf"));
        }

        @Override
        public void putAttachment(AttachmentEntry entry) {
            attachments.add(entry);
        }

        @Override
        public void removeAttachment(PageKey page, String attachmentId) {
            attachments.removeIf(entry -> entry.page().equals(page) && entry.attachmentId().equals(attachmentId));
        }

        @Override
        public Optional<String> text(PageKey key) {
            return Optional.of(text);
        }

        @Override
        public void put(Entry entry) {
        }

        @Override
        public Optional<String> inputSha256(PageKey key) {
            return Optional.empty();
        }

        @Override
        public void remove(PageKey key) {
        }

        @Override
        public void removeSource(String sourceId) {
        }

        @Override
        public void flush() {
        }
    }
}
