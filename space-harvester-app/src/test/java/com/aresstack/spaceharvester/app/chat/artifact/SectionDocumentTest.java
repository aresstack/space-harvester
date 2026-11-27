package com.aresstack.spaceharvester.app.chat.artifact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ein Dokument ist eine flache Liste von Abschnitten mit festen Kennungen (ADR 0045, Abschnitt 2); erfundene Texte. */
class SectionDocumentTest {

    private static final String TEXT = """
            Die Gartenfibel erklärt den Anbau von Bohnen.

            # Gartenfibel

            Ein kurzer Überblick.

            ## Boden

            Lockerer Boden mit viel Humus.

            ```markdown
            # keine Überschrift, weil im Codeblock
            ```

            ### Feuchte

            Gleichmäßig feucht halten.

            ## Aussaat

            Ab Mitte Mai, fünf Zentimeter tief.
            """;

    @Test
    void parsingSplitsAtEveryHeadingAndKeepsTheIntroductionAndCodeBlocks() {
        SectionDocument document = SectionDocument.parse(TEXT);

        assertThat(document.sections()).extracting(SectionDocument.Section::id)
                .containsExactly("a1", "a2", "a3", "a4", "a5");
        assertThat(document.sections()).extracting(SectionDocument.Section::level).containsExactly(0, 1, 2, 3, 2);
        assertThat(document.section("a1").heading()).isEmpty();
        assertThat(document.section("a3").text()).contains("# keine Überschrift, weil im Codeblock");
        assertThat(document.section("a4").heading()).isEqualTo("Feuchte");
        assertThat(SectionDocument.parse(document.markdown()).sections())
                .as("Markdown und Zerlegung sind umkehrbar").isEqualTo(document.sections());
    }

    @Test
    void replacingATextKeepsTheHeadingAndTheOtherSections() {
        SectionDocument document = SectionDocument.parse(TEXT);

        SectionDocument.Change change = document.replaceText("a5", "## Aussaat\n\nAb Ende Mai, drei Zentimeter tief.");

        SectionDocument after = change.document();
        assertThat(change.touched()).containsExactly("a5");
        assertThat(after.section("a5").heading()).isEqualTo("Aussaat");
        assertThat(after.section("a5").text()).isEqualTo("Ab Ende Mai, drei Zentimeter tief.");
        assertThat(after.sections().subList(0, 4)).isEqualTo(document.sections().subList(0, 4));
    }

    @Test
    void headingsInsideAReplacementBecomeNewSectionsWithFreshIds() {
        SectionDocument document = SectionDocument.parse(TEXT);

        SectionDocument.Change change = document.replaceText("a5", "Ab Mitte Mai.\n\n### Abstand\n\nZehn Zentimeter.");

        assertThat(change.touched()).containsExactly("a5", "a6");
        assertThat(change.document().section("a6").level()).isEqualTo(3);
        assertThat(change.document().section("a6").heading()).isEqualTo("Abstand");
        assertThat(change.document().nextId()).isEqualTo(7);
    }

    @Test
    void aLiteralMustOccurExactlyOnce() {
        SectionDocument document = SectionDocument.parse(TEXT);

        assertThat(document.replaceLiteral("a5", "fünf", "vier").document().section("a5").text())
                .isEqualTo("Ab Mitte Mai, vier Zentimeter tief.");
        assertThatThrownBy(() -> document.replaceLiteral("a5", "sechs", "vier"))
                .hasMessageContaining("abschnitt_lesen");
        SectionDocument twice = document.replaceText("a5", "Mai und Mai.").document();
        assertThatThrownBy(() -> twice.replaceLiteral("a5", "Mai", "Juni"))
                .hasMessageContaining("2-mal").hasMessageContaining("Umgebung");
    }

    @Test
    void unknownIdsNameTheExistingOnes() {
        SectionDocument document = SectionDocument.parse(TEXT);

        assertThatThrownBy(() -> document.section("a9"))
                .isInstanceOf(SectionDocument.SectionException.class)
                .hasMessageContaining("a1, a2, a3, a4, a5").hasMessageContaining("dokument_gliederung");
    }

    @Test
    void insertingDeletingRenamingAndMovingNeverReuseIds() {
        SectionDocument document = SectionDocument.parse(TEXT);

        SectionDocument inserted = document.insertAfter("a5", 0, "Ernte", "Nach acht Wochen.").document();
        assertThat(inserted.section("a6").level()).as("Ebene wie davor").isEqualTo(2);
        SectionDocument deleted = inserted.delete("a6").document();
        SectionDocument again = deleted.insertAfter(null, 2, "Vorwort", "").document();
        assertThat(again.sections().get(0).id()).as("nie neu vergeben").isEqualTo("a7");

        SectionDocument renamed = again.rename("a5", "Säen", 0).document();
        assertThat(renamed.section("a5").heading()).isEqualTo("Säen");
        assertThat(renamed.section("a5").level()).isEqualTo(2);
        assertThatThrownBy(() -> renamed.rename("a1", "Titel", 1)).hasMessageContaining("Einleitung");

        SectionDocument moved = renamed.move("a5", "a2").document();
        assertThat(moved.sections()).extracting(SectionDocument.Section::id)
                .containsExactly("a7", "a1", "a2", "a5", "a3", "a4");
        assertThat(renamed.move("a3", null).document().sections().get(0).id()).isEqualTo("a3");
        assertThatThrownBy(() -> renamed.move("a3", "a3")).hasMessageContaining("sich selbst");
    }

    @Test
    void anEmptyDocumentStartsWithAnInsertWithoutAnchor() {
        SectionDocument empty = SectionDocument.parse("");

        assertThat(empty.sections()).isEmpty();
        assertThatThrownBy(() -> empty.section("a1")).hasMessageContaining("leer");
        SectionDocument first = empty.insertAfter("", 1, "# Titel", "Erster Satz.").document();
        assertThat(first.markdown()).isEqualTo("# Titel\n\nErster Satz.\n");
    }

    @Test
    void aShorterFenceInsideALongerOneDoesNotCloseIt() {
        SectionDocument document = SectionDocument.parse(
                "# Anleitung\n\n````markdown\n```\n# kein Abschnitt\n```\n````\n\n## Ende\n\nFertig.");

        assertThat(document.sections()).extracting(SectionDocument.Section::heading)
                .containsExactly("Anleitung", "Ende");
    }

    @Test
    void linkDefinitionsOfAllSectionsAreCollectedOutsideCodeBlocks() {
        SectionDocument document = SectionDocument.parse("# Bohnen\n\nSiehe [Fibel][f].\n\n## Quellen\n\n"
                + "[f]: https://example.org/fibel\n\n```\n[x]: https://example.org/kein\n```");

        assertThat(document.referenceDefinitions()).containsExactly("[f]: https://example.org/fibel");
    }

    @Test
    void aHashThatBelongsToTheHeadingStaysAndAClosingSequenceGoes() {
        SectionDocument document = SectionDocument.parse("# C#\n\nText.\n\n## Rost ##\n\nMehr.");

        assertThat(document.sections()).extracting(SectionDocument.Section::heading).containsExactly("C#", "Rost");
        assertThat(document.markdown()).startsWith("# C#\n");
    }

    @Test
    void indentationAndInnerWhitespaceSurviveParsingAndExport() {
        String markdown = "## Code\n\n    alpha\n    beta\n\n## Gedicht\n\nZeile eins  \nZeile zwei\n";
        SectionDocument document = SectionDocument.parse(markdown);

        assertThat(document.section("a1").text()).isEqualTo("    alpha\n    beta");
        assertThat(document.section("a2").text()).isEqualTo("Zeile eins  \nZeile zwei");
        assertThat(document.markdown()).isEqualTo(markdown);
    }

    @Test
    void aLiteralReplacementWithAHeadingAddsASection() {
        SectionDocument document = SectionDocument.parse("# Brief\n\nOriginal text.");

        SectionDocument.Change change = document.replaceLiteral("a1", "Original text.",
                "Revised text.\n\n## Second\n\nNew section.");

        assertThat(change.touched()).containsExactly("a1", "a2");
        assertThat(change.document().section("a2").heading()).isEqualTo("Second");
        assertThat(change.document().section("a1").text()).isEqualTo("Revised text.");
    }

    @Test
    void theUserReplacesASectionWithHeadingAndText() {
        SectionDocument document = SectionDocument.parse(TEXT);

        SectionDocument.Change change = document.replaceSection("a5", "### Säen\n\nAb Ende Mai.\n\n### Abstand\n\nZehn.");

        assertThat(change.touched()).containsExactly("a5", "a6");
        assertThat(change.document().section("a5").heading()).isEqualTo("Säen");
        assertThat(change.document().section("a5").level()).isEqualTo(3);
        assertThat(change.document().section("a5").text()).isEqualTo("Ab Ende Mai.");
        assertThat(document.replaceSection("a5", "Nur Text.").document().section("a5").heading())
                .as("ohne Überschrift bleibt die alte").isEqualTo("Aussaat");
    }
}
