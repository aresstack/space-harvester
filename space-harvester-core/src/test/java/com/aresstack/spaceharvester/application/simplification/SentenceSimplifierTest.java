package com.aresstack.spaceharvester.application.simplification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Strenges Lesen der Vorstufe (ADR 0032, FL3) mit erfundenen Sätzen. */
class SentenceSimplifierTest {

    private static final SimplificationSection SECTION = new SimplificationSection(
            List.of(new SimplificationSection.Sentence(0, "Ablauf der Wartung")),
            List.of(new SimplificationSection.Sentence(1, "Die Pumpe wird vom Techniker geprüft, wenn sie Geräusche macht."),
                    new SimplificationSection.Sentence(3, "Ersatzteile werden beim Lager angefordert.")));

    @Test
    void theInstructionsAreTheMeasuredVersionOneUnchanged() {
        assertThat(SentenceSimplifier.PROMPT_VERSION).isEqualTo("1");
        assertThat(SentenceSimplifier.instructions())
                .startsWith("Du bereitest deutschen Text für einen Satzparser auf.")
                .endsWith("<Nummer des Eingabesatzes>|<Kennzeichen>|<Satz>\n");
    }

    @Test
    void inputNumbersContextAndChosenSentencesInTextOrder() {
        assertThat(SentenceSimplifier.input(SECTION)).isEqualTo("""
                1|Ablauf der Wartung
                2|Die Pumpe wird vom Techniker geprüft, wenn sie Geräusche macht.
                4|Ersatzteile werden beim Lager angefordert.
                """);
    }

    @Test
    void onlyLinesInTheExactFormatForAChosenSentenceCount() {
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(SECTION, """
                2|T|Der Techniker prüft die Pumpe.
                2|B|Die Pumpe macht Geräusche.
                1|T|Ablauf der Wartung.
                Hier ist die Ausgabe:
                4|X|Ersatzteile werden beim Lager angefordert.
                9|T|Die Pumpe läuft.
                4|T|Ersatzteile werden beim Lager angefordert.
                """);
        assertThat(parsed.sentences().get(1)).extracting(SimplifiedSentence::flag, SimplifiedSentence::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SimplifiedSentence.Flag.FACT,
                                "Der Techniker prüft die Pumpe."),
                        org.assertj.core.groups.Tuple.tuple(SimplifiedSentence.Flag.CONDITION,
                                "Die Pumpe macht Geräusche."));
        assertThat(parsed.sentences().get(3)).hasSize(1);
        assertThat(parsed.rejected()).as("Vorspann, unbekanntes Kennzeichen, fremde Nummer").isEqualTo(3);
        assertThat(parsed.added()).isZero();
        assertThat(parsed.incomplete()).isEmpty();
    }

    @Test
    void aSentenceWithWordsFromOutsideTheSectionIsDiscarded() {
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(SECTION, """
                2|T|Der Hausmeister prüft die Pumpe.
                4|T|Das Lager liefert Ersatzteile.
                """);
        assertThat(parsed.sentences().get(1)).isEmpty();
        assertThat(parsed.sentences().get(3)).isEmpty();
        assertThat(parsed.added()).isEqualTo(2);
        assertThat(parsed.incomplete()).containsExactlyInAnyOrder(1, 3);
    }

    @Test
    void omittedContentWordsMarkTheSentenceIncomplete() {
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(SECTION, "2|T|Der Techniker prüft die Pumpe.\n");
        assertThat(parsed.sentences().get(1)).hasSize(1);
        assertThat(parsed.incomplete()).containsExactlyInAnyOrder(1, 3);
    }

    @Test
    void formsOfTheSameStemCountButOtherVowelsDoNot() {
        assertThat(SentenceSimplifier.sameStem("prüft", "geprüft")).isTrue();
        assertThat(SentenceSimplifier.sameStem("prüfung", "prüft")).isTrue();
        assertThat(SentenceSimplifier.sameStem("fordert", "zurückgefordert")).isTrue();
        assertThat(SentenceSimplifier.sameStem("gibt", "gegeben")).isFalse();
        assertThat(SentenceSimplifier.sameStem("lager", "pumpe")).isFalse();
    }

    @Test
    void aPipeInsideTheSentenceOrAnOverlongSentenceIsRejected() {
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(SECTION,
                "2|T|Der Techniker|prüft.\n2|T|" + "Pumpe ".repeat(300) + "\n");
        assertThat(parsed.sentences().get(1)).isEmpty();
        assertThat(parsed.rejected()).isEqualTo(2);
    }
}
