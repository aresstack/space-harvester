package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ParagraphAlignment.Aligned;
import com.aresstack.spaceharvester.application.changelog.ParagraphAlignment.Kind;
import com.aresstack.spaceharvester.application.changelog.ParagraphAlignment.Step;
import com.aresstack.spaceharvester.application.changelog.ParagraphAlignment.TooLarge;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Zuordnung der Absätze, die der Reiter „Text“ und die Markierung in der Lese-Ansicht teilen. */
class ParagraphAlignmentTest {

    @Test
    void equalTextsAreEqualStepByStep() {
        assertThat(steps(List.of("a", "b"), List.of("a", "b")))
                .containsExactly(new Step(Kind.EQUAL, 0, 0), new Step(Kind.EQUAL, 1, 1));
        assertThat(steps(List.of(), List.of())).isEmpty();
    }

    @Test
    void neighbouringRemovedAndAddedParagraphsPairAsChangedTheRestIsRemovedOrAdded() {
        assertThat(steps(List.of("a", "b", "c", "d"), List.of("a", "x", "c", "e", "f"))).containsExactly(
                new Step(Kind.EQUAL, 0, 0), new Step(Kind.CHANGE, 1, 1), new Step(Kind.EQUAL, 2, 2),
                new Step(Kind.CHANGE, 3, 3), new Step(Kind.ADD, -1, 4));
        assertThat(steps(List.of("a", "b", "c"), List.of("a", "c"))).containsExactly(
                new Step(Kind.EQUAL, 0, 0), new Step(Kind.REMOVE, 1, -1), new Step(Kind.EQUAL, 2, 1));
        // Entfallene vor hinzugekommenen Absätzen, gepaart der Reihe nach.
        assertThat(steps(List.of("a", "b", "c", "z"), List.of("x", "z"))).containsExactly(
                new Step(Kind.CHANGE, 0, 0), new Step(Kind.REMOVE, 1, -1), new Step(Kind.REMOVE, 2, -1),
                new Step(Kind.EQUAL, 3, 1));
    }

    @Test
    void everyParagraphOfBothVersionsAppearsOnceInRisingOrder() {
        List<String> before = List.of("k", "a", "b", "k", "c", "d", "e", "k");
        List<String> after = List.of("a", "k", "x", "c", "k", "e", "y", "k", "k");

        List<Step> steps = steps(before, after);

        assertThat(steps.stream().filter(step -> step.before() >= 0).map(Step::before).toList())
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(steps.stream().filter(step -> step.after() >= 0).map(Step::after).toList())
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8);
        steps.stream().filter(step -> step.kind() == Kind.EQUAL)
                .forEach(step -> assertThat(before.get(step.before())).isEqualTo(after.get(step.after())));
    }

    @Test
    void noCutAndNoLimitOnTheNumberOfStepsWhileTheTabMarksTheSameParagraphsAsChanged() {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            before.add("alt " + i + " " + "x".repeat(600));
            after.add("neu " + i + " " + "y".repeat(600));
        }

        List<Step> steps = steps(before, after);

        assertThat(steps).hasSize(120).allMatch(step -> step.kind() == Kind.CHANGE);
        TextDiff tab = TextContentDiffer.diff(before, after);
        assertThat(tab.truncated()).isTrue();
        // Der Reiter kürzt und begrenzt nur; was er zeigt, sind die ersten Schritte der Zuordnung.
        assertThat(tab.paragraphs()).isNotEmpty().hasSizeLessThan(120)
                .allMatch(change -> change.kind() == ParagraphChange.Kind.CHANGED);
        for (int p = 0; p < tab.paragraphs().size(); p++) {
            assertThat(before.get(steps.get(p).before())).startsWith(tab.paragraphs().get(p).before()
                    .substring(0, tab.paragraphs().get(p).before().length() - 1));
        }
    }

    @Test
    void aMiddlePartBeyondTheCellLimitIsNamedTooLargeAndTheTabShowsItAsReplaced() {
        List<String> before = new ArrayList<>(List.of("anfang"));
        List<String> after = new ArrayList<>(List.of("anfang"));
        for (int i = 0; i < 1000; i++) {
            before.add("alt " + i);
            after.add("neu " + i);
        }
        before.add("ende");
        after.add("ende");
        after.add(after.size() - 1, "noch einer");

        assertThat(ParagraphAlignment.align(before, after)).isEqualTo(new TooLarge(1, 1001, 1002));

        List<Step> replaced = ParagraphAlignment.alignOrReplace(before, after);
        assertThat(replaced.getFirst()).isEqualTo(new Step(Kind.EQUAL, 0, 0));
        assertThat(replaced.get(1)).isEqualTo(new Step(Kind.CHANGE, 1, 1));
        assertThat(replaced.get(replaced.size() - 2)).isEqualTo(new Step(Kind.ADD, -1, 1001));
        assertThat(replaced.getLast()).isEqualTo(new Step(Kind.EQUAL, 1001, 1002));
    }

    private static List<Step> steps(List<String> before, List<String> after) {
        return ((Aligned) ParagraphAlignment.align(before, after)).steps();
    }
}
