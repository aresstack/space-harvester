package com.aresstack.spaceharvester.application.changelog.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Kind;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Nature;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Occurrence;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Role;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Themenvorschläge (ADR 0030, Nachtrag Abschnitt 4, TZ3). Erfundene Beispieldaten, feste Vektoren. */
class TopicProposerTest {

    private static final Instant MONDAY = Instant.parse("2026-11-16T08:00:00Z");
    private final TopicProposer proposer = new TopicProposer(TopicWeights.DEFAULT);

    @Test
    void oneSubjectChangedOnSeveralDaysPagesAndByDifferentPeopleIsOneTopic() {
        // Archivierung an drei Tagen auf drei Seiten in zwei Spaces; dazu eine fremde Änderung und eine Korrektur.
        List<ChangeUnit> units = List.of(
                unit("a", "DEV", "10", "1", 0, Nature.EDIT, Set.of("archivierung", "frist"), true),
                unit("b", "DEV", "11", "1", 2, Nature.NEW_SECTION, Set.of("archivierung", "löschung"), true),
                unit("c", "OPS", "20", "2", 4, Nature.EDIT, Set.of("archivierung", "aufbewahrung"), true),
                unit("d", "OPS", "21", "2", 1, Nature.EDIT, Set.of("kantine", "speiseplan"), true),
                unit("e", "DEV", "12", "1", 3, Nature.CORRECTION, Set.of("archivierung"), true));
        Map<String, float[]> vectors = Map.of(
                "a", new float[] {1f, 0.1f, 0f}, "b", new float[] {0.9f, 0.2f, 0f},
                "c", new float[] {0.95f, 0f, 0.1f}, "d", new float[] {0f, 0f, 1f});

        TopicSet set = proposer.propose("wiki", "1", Optional.empty(), new ChangeUnits.Result(units, 0),
                new TopicProposer.Inputs("model@1", vectors, null, null, List.of()));

        assertThat(set.topics()).hasSize(2);
        ChangeTopic archive = set.topics().getFirst();
        assertThat(archive.unitIds()).containsExactly("a", "b", "c");
        assertThat(archive.spaces()).containsExactlyInAnyOrder("DEV", "OPS");
        assertThat(archive.from()).isEqualTo(MONDAY);
        assertThat(archive.to()).isEqualTo(MONDAY.plus(Duration.ofDays(4)));
        assertThat(archive.label()).isEqualTo("archivierung");
        assertThat(archive.signals()).containsKeys(Signal.VECTOR, Signal.SECTION, Signal.TERMS);
        assertThat(archive.representativeIds()).hasSize(2);
        assertThat(set.topics().get(1).unitIds()).containsExactly("d");
        assertThat(set.corrections()).isEqualTo(1);
        assertThat(set.topics()).noneMatch(topic -> topic.unitIds().contains("e"));
        assertThat(set.signals()).containsExactlyInAnyOrder(Signal.VECTOR, Signal.SECTION, Signal.TERMS);
        assertThat(set.missingSignals()).doesNotContain(Signal.VECTOR, Signal.TERMS);
        assertThat(set.method()).isEqualTo(TopicProposer.METHOD);
    }

    @Test
    void withoutVectorsStructureAndTermsGroupAndTheSetSaysWhatWasMissing() {
        List<ChangeUnit> units = List.of(
                unit("a", "DEV", "10", "1", 0, Nature.EDIT, Set.of("archivierung", "frist"), true),
                unit("b", "DEV", "11", "1", 1, Nature.EDIT, Set.of("archivierung", "frist", "löschung"), true),
                unit("c", "OPS", "20", "2", 2, Nature.EDIT, Set.of("kantine"), true));

        TopicSet set = proposer.propose("wiki", "1", Optional.of("DEV"), new ChangeUnits.Result(units, 0),
                TopicProposer.Inputs.NONE);

        assertThat(set.signals()).doesNotContain(Signal.VECTOR, Signal.STATEMENTS, Signal.LABELS);
        // STATEMENTS hat im Standard kein Gewicht und fehlt daher nicht; SECTION nur ohne Überschriftenpfad.
        assertThat(set.missingSignals()).containsExactlyInAnyOrder(Signal.VECTOR, Signal.LABELS, Signal.REFERENCES,
                Signal.SECTION);
        assertThat(set.topics()).extracting(ChangeTopic::unitIds)
                .containsExactlyInAnyOrder(List.of("a", "b"), List.of("c"));
        assertThat(set.spaceKey()).contains("DEV");
    }

    @Test
    void unitsWithoutAiReleaseAreInNoTopicAndOnlyCounted() {
        List<ChangeUnit> units = List.of(
                unit("a", "DEV", "10", "1", 0, Nature.EDIT, Set.of("archivierung"), true),
                unit("b", "OPS", "20", "2", 0, Nature.EDIT, Set.of("kantine"), false));
        // Ein Vektor für b dürfte es gar nicht geben; selbst wenn, kommt b in kein Thema.
        Map<String, float[]> vectors = Map.of("a", new float[] {1f, 0f}, "b", new float[] {1f, 0f});

        TopicSet set = proposer.propose("wiki", "1", Optional.empty(), new ChangeUnits.Result(units, 0),
                new TopicProposer.Inputs("model@1", vectors, null, null, List.of()));

        assertThat(set.notReleased()).isEqualTo(1);
        assertThat(set.topics()).singleElement().satisfies(topic -> assertThat(topic.unitIds()).containsExactly("a"));
        assertThat(set.ungroupedUnitIds()).doesNotContain("b");
    }

    @Test
    void theSameInputGivesTheSameTopicsAndOtherWeightsAnotherHash() {
        List<ChangeUnit> units = List.of(
                unit("a", "DEV", "10", "1", 0, Nature.EDIT, Set.of("archivierung"), true),
                unit("b", "DEV", "11", "1", 1, Nature.EDIT, Set.of("archivierung"), true));
        ChangeUnits.Result result = new ChangeUnits.Result(units, 0);

        TopicSet first = proposer.propose("wiki", "1", Optional.empty(), result, TopicProposer.Inputs.NONE);
        TopicSet second = proposer.propose("wiki", "1", Optional.empty(), result, TopicProposer.Inputs.NONE);
        TopicSet tuned = new TopicProposer(new TopicWeights("w2", TopicWeights.DEFAULT.weights(), 0.4, 2_000))
                .propose("wiki", "1", Optional.empty(), result, TopicProposer.Inputs.NONE);

        assertThat(second).isEqualTo(first);
        assertThat(tuned.inputSha256()).isNotEqualTo(first.inputSha256());
    }

    @Test
    void aLargeUpdateStaysWithinTimeAndTheRestBeyondTheLimitIsReported() {
        List<ChangeUnit> units = new ArrayList<>();
        Map<String, float[]> vectors = new HashMap<>();
        for (int i = 0; i < 2_500; i++) {
            String id = String.format("u%05d", i);
            units.add(unit(id, "S" + (i % 7), "p" + i, "parent" + (i % 50), i % 5, Nature.EDIT,
                    Set.of("thema" + (i % 40), "begriff" + (i % 13)), true));
            vectors.put(id, new float[] {(float) Math.sin(i % 40), (float) Math.cos(i % 40), (i % 3) / 3f});
        }
        TopicProposer limited = new TopicProposer(new TopicWeights("w1", TopicWeights.DEFAULT.weights(), 0.55,
                2_000));

        TopicSet set = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> limited.propose("wiki", "1",
                Optional.empty(), new ChangeUnits.Result(units, 0),
                new TopicProposer.Inputs("model@1", vectors, null, null, List.of())));

        assertThat(set.ungroupedUnitIds()).hasSize(500);
        assertThat(set.topics().stream().mapToInt(topic -> topic.unitIds().size()).sum()).isEqualTo(2_000);
        assertThat(set.topics().size()).isLessThan(2_000);
    }

    private static ChangeUnit unit(String id, String space, String pageId, String parentId, int day, Nature nature,
                                   Set<String> terms, boolean released) {
        Occurrence occurrence = new Occurrence(new PageKey("wiki", pageId), space, "Seite " + pageId,
                List.of("Handbuch", "Kapitel " + parentId), null, parentId, List.of(),
                MONDAY.plus(Duration.ofDays(day)), Role.CHANGED, 0, 0);
        return new ChangeUnit(id, Kind.PARAGRAPH_CHANGED, nature, "alt", "neu", List.of("alt"), List.of("neu"),
                List.of(occurrence), terms, 120, released);
    }
}
