package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ParagraphChange.Kind;
import com.aresstack.spaceharvester.application.enrichment.PageText;
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

    @Test
    void eachChangedParagraphCarriesTheHeadingPathOfItsSection() {
        String before = "Vorwort\nBetrieb\nAlt\nNotfall\nWeg";
        String after = "Vorwort neu\nBetrieb\nNeu\nNotfall";
        List<List<String>> beforeHeadings = new ArrayList<>();
        List<List<String>> afterHeadings = new ArrayList<>();
        List<String> x = TextContentDiffer.paragraphs(new PageText(
                "t", before, List.of(section("Betrieb", 1, before), section("Notfall", 2, before))), beforeHeadings);
        List<String> y = TextContentDiffer.paragraphs(new PageText(
                "t", after, List.of(section("Betrieb", 1, after), section("Notfall", 2, after))), afterHeadings);

        TextDiff diff = TextContentDiffer.diff(x, y, beforeHeadings, afterHeadings);

        assertThat(diff.paragraphs()).containsExactly(
                new ParagraphChange(Kind.CHANGED, "Vorwort", "Vorwort neu", List.of()),
                new ParagraphChange(Kind.CHANGED, "Alt", "Neu", List.of("Betrieb")),
                new ParagraphChange(Kind.REMOVED, "Weg", null, List.of("Betrieb", "Notfall")));
    }

    @Test
    void theHeadingPathIsLimitedToTheDeepestLevelsAndShortHeadings() {
        StringBuilder text = new StringBuilder();
        List<PageText.Section> sections = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            String heading = level == 6 ? "h".repeat(300) : "H" + level;
            int start = text.length();
            text.append(heading).append('\n');
            sections.add(new PageText.Section(heading, level,
                    start, start));
        }
        text.append("Absatz");
        List<PageText.Section> closed = new ArrayList<>();
        for (var section : sections) {
            closed.add(new PageText.Section(section.heading(),
                    section.level(), section.start(), text.length()));
        }
        List<List<String>> headings = new ArrayList<>();

        TextContentDiffer.paragraphs(new PageText("t",
                text.toString(), closed), headings);

        List<String> last = headings.getLast();
        assertThat(last).hasSize(TextContentDiffer.MAX_HEADINGS).startsWith("H3");
        assertThat(last.getLast()).hasSize(TextContentDiffer.MAX_HEADING).endsWith("…");
    }

    private static PageText.Section section(String heading, int level, String text) {
        int start = text.indexOf("\n" + heading) + 1;
        return new PageText.Section(heading, level, start,
                text.length());
    }
}
