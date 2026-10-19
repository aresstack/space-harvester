package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link TopicClusterer} wie im Enterprise AI Client ({@code TopicClustererTest}): inhaltsgleiche Absätze landen in
 * einem Thema, verschiedene getrennt, deterministisch (#181, ADR 0020).
 */
class TopicClustererTest {

    private static final Vocabulary VOCABULARY = new Vocabulary("drucker", "toner", "urlaub", "antrag");

    /** Zählt je Wort des Vokabulars die Vorkommen, wie {@code VocabularyEmbeddingPort} im Client. */
    record Vocabulary(String... words) {

        float[] vector(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            float[] values = new float[words.length];
            for (int i = 0; i < words.length; i++) {
                int from = 0;
                while ((from = lower.indexOf(words[i], from)) >= 0) {
                    values[i]++;
                    from += words[i].length();
                }
            }
            return values;
        }
    }

    private static TopicClusterer.Member member(String id, String text) {
        return new TopicClusterer.Member(id, text, VOCABULARY.vector(text));
    }

    private static List<TopicClusterer.Member> corpus() {
        return List.of(
                member("p1", "Der Drucker braucht Toner. Toner liegt im Schrank."),
                member("p2", "Urlaub beantragt man mit dem Antrag. Der Antrag geht an das Personal."),
                member("p3", "Ist der Toner leer, meldet der Drucker einen Fehler."),
                member("p4", "Für den Urlaub reicht ein Antrag zwei Wochen vorher."));
    }

    @Test
    void groupsPassagesOfTheSameTopic() {
        List<TopicClusterer.Topic> topics = new TopicClusterer().cluster(corpus());

        assertThat(topics).hasSize(2);
        assertThat(topics.get(0).memberIds()).containsExactlyInAnyOrder("p1", "p3");
        assertThat(topics.get(1).memberIds()).containsExactlyInAnyOrder("p2", "p4");
        assertThat(topics.get(0).topicId()).isEqualTo("p1");
        assertThat(topics.get(0).confidence()).isGreaterThan(0.9);
    }

    @Test
    void titleIsTheFirstSentenceOfTheRepresentative() {
        TopicClusterer.Topic topic = new TopicClusterer().cluster(corpus()).getFirst();

        assertThat(topic.representativeIds().getFirst()).isIn("p1", "p3");
        assertThat(topic.suggestedTitle()).isIn("Der Drucker braucht Toner",
                "Ist der Toner leer, meldet der Drucker einen Fehler");
    }

    @Test
    void clusteringIsDeterministic() {
        List<String> first = describe(new TopicClusterer().cluster(corpus()));
        for (int i = 0; i < 5; i++) {
            assertThat(describe(new TopicClusterer().cluster(corpus()))).isEqualTo(first);
        }
    }

    private static List<String> describe(List<TopicClusterer.Topic> topics) {
        return topics.stream().map(topic -> topic.topicId() + topic.memberIds() + topic.representativeIds()
                + topic.suggestedTitle()).toList();
    }
}
