package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Zerlegung in Passagen wie im Enterprise AI Client, mit Stellen im Text (#181, ADR 0020). */
class PassageSegmentationTest {

    static final String CONFLUENCE = "confluence-storage-text/v1";

    /** Zwei Themen in einem Absatz: Katzen, dann Server. */
    static final String TOPICS = "Die Katze schläft im Korb. Die Katze frisst am Abend. Die Katze spielt mit Wolle. "
            + "Der Server startet um acht. Der Server läuft stabil. Der Server stoppt nachts.";

    /** Je Satz ein Vektor nach seinem Thema; zählt die Aufrufe. */
    static final class TopicEmbedder implements Function<List<String>, List<float[]>> {

        final List<List<String>> calls = new ArrayList<>();

        @Override
        public List<float[]> apply(List<String> texts) {
            calls.add(texts);
            return texts.stream().map(TopicEmbedder::vector).toList();
        }

        static float[] vector(String text) {
            if (text.contains("Katze")) {
                return new float[] {1, 0, 0};
            }
            if (text.contains("Server")) {
                return new float[] {0, 1, 0};
            }
            return new float[] {0, 0, 1};
        }
    }

    private final PassageSegmentation segmentation = new PassageSegmentation(
            SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()), KnowledgeTextSegmentation.defaults());

    @Test
    void splitsAParagraphAtTheTopicChangeOfTheSentenceWindows() {
        PageText text = new PageText(CONFLUENCE, TOPICS, List.of());

        PassageSegmentation.Result result = segmentation.segment(text, new TopicEmbedder());

        assertThat(result.semantic()).isTrue();
        assertThat(result.sentences()).isEqualTo(6);
        assertThat(result.passages()).extracting(p -> TOPICS.substring(p.start(), p.end())).containsExactly(
                "Die Katze schläft im Korb. Die Katze frisst am Abend. Die Katze spielt mit Wolle.",
                "Der Server startet um acht. Der Server läuft stabil. Der Server stoppt nachts.");
        PassageSegmentation.Passage second = result.passages().get(1);
        assertThat(second.boundary()).isEqualTo(PassageSegmentation.Boundary.TOPIC);
        assertThat(second.similarity()).isEqualTo(0.0);
        assertThat(second.firstSentence()).isEqualTo(3);
        assertThat(second.sentences()).isEqualTo(3);
        assertThat(result.passages().get(0).boundary()).isEqualTo(PassageSegmentation.Boundary.STRUCTURE);
    }

    @Test
    void withoutModelOnlyTheStructureDecides() {
        PageText text = new PageText(CONFLUENCE, TOPICS, List.of());

        PassageSegmentation.Result result = segmentation.segment(text, null);

        assertThat(result.semantic()).isFalse();
        assertThat(result.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.start()).isZero();
            assertThat(passage.end()).isEqualTo(TOPICS.length());
            assertThat(passage.sentences()).isEqualTo(6);
            assertThat(passage.boundary()).isEqualTo(PassageSegmentation.Boundary.STRUCTURE);
            assertThat(passage.similarity()).isNull();
        });
    }

    @Test
    void headingsListsAndTableRowsAreHardBoundariesWithTheirSectionPath() {
        String content = "Handbuch\nEinleitung mit einem Satz.\nBetrieb\nErster Punkt der Liste.\n"
                + "Spalte A | Spalte B\nDer Server läuft. Die Katze schläft. Der Server stoppt.";
        int handbook = 0;
        int operation = content.indexOf("Betrieb");
        PageText text = new PageText(CONFLUENCE, content, List.of(
                new PageText.Section("Handbuch", 1, handbook, operation),
                new PageText.Section("Betrieb", 2, operation, content.length())));

        PassageSegmentation.Result result = segmentation.segment(text, new TopicEmbedder());

        assertThat(result.passages()).extracting(p -> content.substring(p.start(), p.end()),
                PassageSegmentation.Passage::section, PassageSegmentation.Passage::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Einleitung mit einem Satz.", "Handbuch",
                        PassageSegmentation.Kind.PARAGRAPH),
                org.assertj.core.groups.Tuple.tuple("Erster Punkt der Liste.", "Handbuch > Betrieb",
                        PassageSegmentation.Kind.PARAGRAPH),
                org.assertj.core.groups.Tuple.tuple("Spalte A | Spalte B", "Handbuch > Betrieb",
                        PassageSegmentation.Kind.TABLE_ROW),
                org.assertj.core.groups.Tuple.tuple("Der Server läuft. Die Katze schläft. Der Server stoppt.",
                        "Handbuch > Betrieb", PassageSegmentation.Kind.PARAGRAPH));
    }

    @Test
    void plainTextFilesUseBlankLinesListsAndCodeFencesLikeTheClient() {
        String content = "Erster Absatz,\numbrochen über zwei Zeilen.\n\n- ein Punkt\n```\ncode();\n```\n> Zitat";
        PageText text = new PageText("file-text/v1", content, List.of());

        PassageSegmentation.Result result = segmentation.segment(text, null);

        assertThat(result.passages()).extracting(p -> content.substring(p.start(), p.end()),
                PassageSegmentation.Passage::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Erster Absatz,\numbrochen über zwei Zeilen.",
                        PassageSegmentation.Kind.PARAGRAPH),
                org.assertj.core.groups.Tuple.tuple("- ein Punkt", PassageSegmentation.Kind.LIST_ITEM),
                org.assertj.core.groups.Tuple.tuple("code();", PassageSegmentation.Kind.CODE),
                org.assertj.core.groups.Tuple.tuple("Zitat", PassageSegmentation.Kind.QUOTE));
    }

    @Test
    void longParagraphsEndAfterEightSentencesAndLongPassagesAtTheTokenBudget() {
        StringBuilder same = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            same.append("Die Katze schläft lange. ");
        }
        String words = "wort ".repeat(PassageSegmentation.MAX_PASSAGE_TOKENS + 10).strip();
        String content = same.toString().strip() + "\n" + words;
        PageText text = new PageText(CONFLUENCE, content, List.of());

        List<PassageSegmentation.Passage> passages = segmentation.segment(text, new TopicEmbedder()).passages();

        assertThat(passages).extracting(PassageSegmentation.Passage::sentences,
                PassageSegmentation.Passage::boundary).containsExactly(
                org.assertj.core.groups.Tuple.tuple(8, PassageSegmentation.Boundary.STRUCTURE),
                org.assertj.core.groups.Tuple.tuple(2, PassageSegmentation.Boundary.LENGTH),
                org.assertj.core.groups.Tuple.tuple(1, PassageSegmentation.Boundary.STRUCTURE),
                org.assertj.core.groups.Tuple.tuple(1, PassageSegmentation.Boundary.TOKENS));
        assertThat(passages.get(2).end()).isLessThan(passages.get(3).start());
        assertThat(passages.get(3).end()).isEqualTo(content.length());
    }

    @Test
    void isDeterministicAndEmbedsAllSentencesInBatches() {
        PageText text = new PageText(CONFLUENCE, TOPICS + "\n" + TOPICS, List.of());
        TopicEmbedder embedder = new TopicEmbedder();

        PassageSegmentation.Result first = segmentation.segment(text, embedder);
        PassageSegmentation.Result second = segmentation.segment(text, new TopicEmbedder());

        assertThat(first).isEqualTo(second);
        assertThat(embedder.calls).hasSize(1);
        assertThat(embedder.calls.get(0)).hasSize(12);
    }

    @Test
    void fingerprintNamesTheValuesOfTheClientAndTheSentenceMethod() {
        assertThat(segmentation.fingerprint())
                .isEqualTo("semantic-v2;window=3;threshold=0.35;min=2;max=8;maxTokens=350;sentences=splitter");
    }
}
