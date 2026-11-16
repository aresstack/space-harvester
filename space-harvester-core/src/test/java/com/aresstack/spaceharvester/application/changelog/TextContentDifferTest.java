package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ParagraphChange.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR 0030, Abschnitt 4 (CL5): Unterschied der Lesetexte nach Absätzen, in der Länge begrenzt. */
class TextContentDifferTest {

    private static TextDiff diff(String before, String after) {
        return TextContentDiffer.diff(TextContentDiffer.paragraphs(before), TextContentDiffer.paragraphs(after));
    }

    @Test
    void sameTextHasNoChanges() {
        TextDiff diff = diff("Eins\n\nZwei\n", "  Eins\nZwei");

        assertThat(diff.paragraphs()).isEmpty();
        assertThat(diff.truncated()).isFalse();
    }

    @Test
    void addedRemovedAndChangedParagraphsInTextOrder() {
        TextDiff diff = diff("Anfang\nAlt\nMitte\nWeg\nEnde", "Anfang\nNeu\nMitte\nEnde\nDazu");

        assertThat(diff.paragraphs()).containsExactly(
                new ParagraphChange(Kind.CHANGED, "Alt", "Neu"),
                new ParagraphChange(Kind.REMOVED, "Weg", null),
                new ParagraphChange(Kind.ADDED, null, "Dazu"));
    }

    @Test
    void anInsertedParagraphDoesNotMarkTheFollowingOnesAsChanged() {
        TextDiff diff = diff("A\nB\nC\nD", "A\nX\nB\nC\nD");

        assertThat(diff.paragraphs()).containsExactly(new ParagraphChange(Kind.ADDED, null, "X"));
    }

    @Test
    void longParagraphsAreCutAndManyChangesAreTruncated() {
        String longParagraph = "x".repeat(TextContentDiffer.MAX_PARAGRAPH + 100);
        TextDiff cut = diff("kurz", longParagraph);
        assertThat(cut.paragraphs()).singleElement().satisfies(change -> {
            assertThat(change.after()).hasSize(TextContentDiffer.MAX_PARAGRAPH).endsWith("…");
            assertThat(change.before()).isEqualTo("kurz");
        });

        List<String> many = new ArrayList<>();
        for (int i = 0; i < TextContentDiffer.MAX_CHANGES + 10; i++) {
            many.add("Absatz " + i);
        }
        TextDiff truncated = TextContentDiffer.diff(List.of(), many);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.paragraphs()).hasSize(TextContentDiffer.MAX_CHANGES);
    }

    @Test
    void theTotalLengthIsBounded() {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            before.add(i + "a".repeat(400));
            after.add(i + "b".repeat(400));
        }
        TextDiff diff = TextContentDiffer.diff(before, after);

        assertThat(diff.truncated()).isTrue();
        int total = diff.paragraphs().stream()
                .mapToInt(change -> change.before().length() + change.after().length()).sum();
        assertThat(total).isLessThanOrEqualTo(TextContentDiffer.MAX_TOTAL);
    }
}
