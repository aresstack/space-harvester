package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Markdown-Ansicht der Blöcke (ADR 0026, AP6c). */
class MarkdownViewTest {

    @Test
    void blocksBecomeMarkdown() {
        String markdown = MarkdownView.of(List.of(
                new Block.Heading(0, Optional.empty(), List.of(), 2, "Ziele"),
                new Block.Paragraph(1, Optional.empty(), List.of(), "Erster Absatz."),
                new Block.ListItem(2, Optional.empty(), List.of(), false, 1, "eins"),
                new Block.ListItem(3, Optional.empty(), List.of(), false, 2, "unter eins"),
                new Block.ListItem(4, Optional.empty(), List.of(), true, 1, "zwei"),
                new Block.Code(5, Optional.empty(), List.of(), "int x = 1;", Optional.of("java"))));

        assertThat(markdown).isEqualTo("""
                ## Ziele

                Erster Absatz.

                - eins
                  - unter eins
                1. zwei

                ```java
                int x = 1;
                ```
                """);
    }

    @Test
    void sourceTextIsNeverReadAsMarkup() {
        String markdown = MarkdownView.of(List.of(
                new Block.Paragraph(0, Optional.empty(), List.of(), "# kein Titel *kein Fett* [kein](Verweis) a|b"),
                new Block.Paragraph(1, Optional.empty(), List.of(), "1. keine Liste\n- auch keine"),
                new Block.Code(2, Optional.empty(), List.of(), "```\nZaun im Code\n```", Optional.empty())));

        assertThat(markdown).startsWith("\\# kein Titel \\*kein Fett\\* \\[kein\\](Verweis) a\\|b\n\n"
                + "1\\. keine Liste\\\n\\- auch keine\n\n");
        assertThat(markdown).contains("````\n```\nZaun im Code\n```\n````");
    }

    @Test
    void tablesBecomeGfmTablesAndContinuedPiecesGoOn() {
        Block.Table first = new Block.Table(0, Optional.empty(), List.of(), List.of(
                new Block.Cell(0, 0, "Name", true, Optional.empty()), new Block.Cell(0, 1, "Wert", true, Optional.empty()),
                new Block.Cell(1, 0, "a|b", false, Optional.empty()), new Block.Cell(1, 1, "1", false, Optional.empty())),
                false);
        Block.Table second = new Block.Table(1, Optional.empty(), List.of(), List.of(
                new Block.Cell(2, 0, "c", false, Optional.empty()), new Block.Cell(2, 1, "zwei\nZeilen", false,
                        Optional.empty())), true);
        Block.Table plain = new Block.Table(2, Optional.empty(), List.of(), List.of(
                new Block.Cell(0, 0, "x", false, Optional.empty())), false);

        assertThat(MarkdownView.of(List.of(first, second, plain))).isEqualTo("""
                | Name | Wert |
                | --- | --- |
                | a\\|b | 1 |
                | c | zwei Zeilen |

                |  |
                | --- |
                | x |
                """);
    }

    @Test
    void linksGoToTheirLabelOrFollowTheBlock() {
        String markdown = MarkdownView.of(List.of(
                new Block.Paragraph(0, Optional.empty(), List.of(
                        new Link("https://example.org/a", Optional.of("Seite A")),
                        new Link("javascript:alert(1)", Optional.of("Gefahr")),
                        new Link("https://example.org/b c", Optional.of("anderswo"))),
                        "Siehe Seite A und Gefahr."),
                new Block.Table(1, Optional.empty(), List.of(new Link("https://example.org/z", Optional.of("Zelle"))),
                        List.of(new Block.Cell(0, 0, "Zelle", false, Optional.empty())), false)));

        assertThat(markdown).isEqualTo("""
                Siehe [Seite A](https://example.org/a) und Gefahr.

                javascript:alert(1)\\
                [anderswo](<https://example.org/b c>)

                |  |
                | --- |
                | [Zelle](https://example.org/z) |
                """);
    }

    @Test
    void locationsAppearOnceWhenTheyChange() {
        String markdown = MarkdownView.of(List.of(
                new Block.Paragraph(0, Optional.of(Location.page(1)), List.of(), "a"),
                new Block.Paragraph(1, Optional.of(Location.page(1)), List.of(), "b"),
                new Block.Paragraph(2, Optional.of(Location.page(3)), List.of(), "c"),
                new Block.Paragraph(3, Optional.of(Location.sheet("Blatt --> 1")), List.of(), "d")));

        assertThat(markdown).isEqualTo("""
                <!-- page 1 -->

                a

                b

                <!-- page 3 -->

                c

                <!-- sheet: Blatt - -&gt; 1 -->

                d
                """);
    }

    @Test
    void anEmptyDocumentIsEmpty() {
        assertThat(MarkdownView.of(List.of())).isEmpty();
    }
}
