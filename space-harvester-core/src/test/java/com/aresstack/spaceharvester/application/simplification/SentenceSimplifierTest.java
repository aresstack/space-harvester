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
        assertThat(SentenceSimplifier.input(SECTION)).as("wie gemessen: Nummer, Leerzeichen, Satz; fortlaufend ab 1")
                .isEqualTo("""
                1 Ablauf der Wartung
                2 Die Pumpe wird vom Techniker geprüft, wenn sie Geräusche macht.
                3 Ersatzteile werden beim Lager angefordert.
                """);
    }

    @Test
    void onlyLinesInTheExactFormatForAChosenSentenceCount() {
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(SECTION, """
                2|T|Der Techniker prüft die Pumpe.
                2|B|Die Pumpe macht Geräusche.
                1|T|Ablauf der Wartung.
                Hier ist die Ausgabe:
                3|X|Ersatzteile werden beim Lager angefordert.
                9|T|Die Pumpe läuft.
                3|T|Ersatzteile werden beim Lager angefordert.
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
                3|T|Das Lager liefert Ersatzteile.
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
        // ein anderes Wort davor ist neue Information
        assertThat(SentenceSimplifier.sameStem("vertrag", "mietvertrag")).isFalse();
        assertThat(SentenceSimplifier.related("Teil", "Vorteil")).as("Nomen: Wort davor ist neu").isFalse();
        assertThat(SentenceSimplifier.related("Vertrag", "Mietvertrag")).isFalse();
        assertThat(SentenceSimplifier.related("stellt", "einstellt")).as("Verbform mit Verbzusatz").isTrue();
        assertThat(SentenceSimplifier.related("Prüfung", "geprüft")).isTrue();
    }

    @Test
    void umlautPluralsSecondEndingsDerivedNounsAndSeparatedParticlesCount() {
        assertThat(SentenceSimplifier.related("Verträge", "Vertrag")).as("Mehrzahl mit Umlaut").isTrue();
        assertThat(SentenceSimplifier.related("Ventils", "Ventil")).as("zweite Endung").isTrue();
        assertThat(SentenceSimplifier.related("Dichtheit", "dicht")).as("Nomen aus Eigenschaft").isTrue();
        assertThat(SentenceSimplifier.related("festgestellt", "feststellen")).as("„ge“ nach Verbzusatz").isTrue();
        assertThat(SentenceSimplifier.related("vorlegen", "vorzulegen")).as("„zu“ nach Verbzusatz").isTrue();
        assertThat(SentenceSimplifier.related("stellt", "feststellt")).isTrue();
        assertThat(SentenceSimplifier.related("fest", "feststellt")).as("abgetrennter Verbzusatz").isTrue();
        assertThat(SentenceSimplifier.related("gibt", "gegeben")).as("starke Form bleibt Grenze").isFalse();
        assertThat(SentenceSimplifier.related("Ventil", "Venus")).as("gemeinsamer Anfang ist kein Stamm").isFalse();
    }

    @Test
    void aDutyWithZuInfinitiveCoversMussButAPlanDoesNot() {
        SimplificationSection duty = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Die Pumpe ist vom Techniker jährlich vorzulegen.")));
        SentenceSimplifier.Parsed kept = SentenceSimplifier.parse(duty,
                "1|T|Der Techniker muss die Pumpe jährlich vorlegen.\n");
        assertThat(kept.sentences().get(0)).hasSize(1);
        assertThat(SentenceSimplifier.parse(duty, "1|T|Der Techniker legt die Pumpe jährlich vor.\n").lost())
                .as("eine nur ausgedrückte Pflicht fehlt nie").isEmpty();
        SimplificationSection plan = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Es war geplant, die Pumpe jährlich zu prüfen.")));
        assertThat(SentenceSimplifier.parse(plan, "1|P|Der Techniker muss die Pumpe jährlich prüfen.\n").added())
                .isEqualTo(1);
    }

    @Test
    void anInventedActorFromAVerbIsDiscarded() {
        SimplificationSection section = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Die Unterlagen werden geprüft.")));
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(section, "1|T|Der Prüfer prüft die Unterlagen.\n");
        assertThat(parsed.sentences().get(0)).isEmpty();
        assertThat(parsed.added()).isEqualTo(1);
        assertThat(SentenceSimplifier.related("Prüfers", "Prüfer")).as("Form auf -er im Abschnitt").isTrue();
    }

    @Test
    void zuBeforeANounOrFunctionWordIsNoDuty() {
        SimplificationSection opening = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Der Zugang zum Lager ist zu den Geschäftszeiten möglich.")));
        assertThat(SentenceSimplifier.parse(opening,
                "1|T|Der Zugang zum Lager muss zu den Geschäftszeiten möglich sein.\n").added()).isEqualTo(1);
        SimplificationSection access = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Der Techniker hat Zugang zu allen Pumpen.")));
        assertThat(SentenceSimplifier.parse(access, "1|T|Der Techniker muss Zugang zu allen Pumpen haben.\n")
                .added()).isEqualTo(1);
    }

    @Test
    void anOrderAdverbNeedsTheMatchingConjunction() {
        SimplificationSection after = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Der Techniker prüft die Pumpe, nachdem das Lager sie geliefert hat.")));
        assertThat(SentenceSimplifier.parse(after, "1|T|Danach prüft der Techniker die Pumpe.\n")
                .sentences().get(0)).hasSize(1);
        SimplificationSection plain = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Der Techniker prüft die Pumpe.")));
        assertThat(SentenceSimplifier.parse(plain, "1|T|Danach prüft der Techniker die Pumpe.\n").added())
                .isEqualTo(1);
    }

    @Test
    void aForeignCompoundIsDiscarded() {
        SimplificationSection section = new SimplificationSection(List.of(),
                List.of(new SimplificationSection.Sentence(0, "Der Vertrag wird vom Vermieter gekündigt.")));
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(section,
                "1|T|Der Vermieter kündigt den Mietvertrag.\n");
        assertThat(parsed.sentences().get(0)).isEmpty();
        assertThat(parsed.added()).isEqualTo(1);
    }

    @Test
    void aNegationOrModalVerbMustNotBeInventedNorLost() {
        SimplificationSection section = new SimplificationSection(List.of(),
                List.of(new SimplificationSection.Sentence(0, "Die Pumpe darf nicht vom Techniker geöffnet werden."),
                        new SimplificationSection.Sentence(1, "Der Techniker prüft die Pumpe.")));
        SentenceSimplifier.Parsed lost = SentenceSimplifier.parse(section, """
                1|T|Der Techniker öffnet die Pumpe.
                2|T|Der Techniker prüft die Pumpe nicht.
                """);
        // Verneinung und Modalverb des Originals fehlen: der Satz sagte das Gegenteil, also wird er verworfen
        assertThat(lost.sentences().get(0)).isEmpty();
        assertThat(lost.incomplete()).contains(0);
        assertThat(lost.lost()).containsExactly(0);
        assertThat(lost.sentences().get(1)).as("Verneinung steht im Abschnitt, hinzufügen ist kein Verlust")
                .hasSize(1);
        SentenceSimplifier.Parsed invented = SentenceSimplifier.parse(section, """
                1|T|Der Techniker darf die Pumpe nicht öffnen.
                2|T|Der Techniker muss die Pumpe prüfen.
                2|T|Der Techniker prüft keine Pumpe.
                """);
        assertThat(invented.sentences().get(0)).hasSize(1);
        assertThat(invented.incomplete()).doesNotContain(0);
        assertThat(invented.lost()).isEmpty();
        assertThat(invented.sentences().get(1)).as("„muss“ und „kein“ stehen nicht im Abschnitt").isEmpty();
        assertThat(invented.added()).isEqualTo(2);
    }

    @Test
    void aModalVerbKeptInOneOfSeveralSentencesIsNoLoss() {
        SimplificationSection section = new SimplificationSection(List.of(), List.of(new SimplificationSection
                .Sentence(0, "Die Pumpe muss geprüft und vom Techniker gereinigt werden.")));
        SentenceSimplifier.Parsed kept = SentenceSimplifier.parse(section, """
                1|T|Der Techniker muss die Pumpe prüfen.
                1|T|Der Techniker reinigt die Pumpe.
                """);
        assertThat(kept.lost()).isEmpty();
        assertThat(kept.sentences().get(0)).hasSize(2);
        SentenceSimplifier.Parsed dropped = SentenceSimplifier.parse(section, """
                1|T|Der Techniker prüft die Pumpe.
                1|T|Der Techniker reinigt die Pumpe.
                """);
        assertThat(dropped.lost()).containsExactly(0);
        assertThat(dropped.sentences().get(0)).isEmpty();
    }

    @Test
    void numbersMustNotBeInventedNorLost() {
        SimplificationSection section = new SimplificationSection(List.of(),
                List.of(new SimplificationSection.Sentence(0, "Bis 2030 werden 400 Pumpen vom Lager geliefert.")));
        SentenceSimplifier.Parsed invented = SentenceSimplifier.parse(section,
                "1|P|Das Lager liefert bis 2031 400 Pumpen.\n");
        assertThat(invented.sentences().get(0)).isEmpty();
        assertThat(invented.added()).isEqualTo(1);
        SentenceSimplifier.Parsed lost = SentenceSimplifier.parse(section, "1|P|Das Lager liefert bis 2030 Pumpen.\n");
        assertThat(lost.sentences().get(0)).hasSize(1);
        assertThat(lost.incomplete()).containsExactly(0);
    }

    @Test
    void shortAbbreviationsAreContentWords() {
        assertThat(SentenceSimplifier.contentWords("Die IT prüft die ZfA und das Amt.")).containsExactly("it",
                "prüft", "zfa");
        SimplificationSection section = new SimplificationSection(List.of(),
                List.of(new SimplificationSection.Sentence(0, "Die Pumpe wird von der IT geprüft.")));
        assertThat(SentenceSimplifier.parse(section, "1|T|Die EDV prüft die Pumpe.\n").added()).isEqualTo(1);
        assertThat(SentenceSimplifier.parse(section, "1|T|Der Techniker prüft die Pumpe.\n").incomplete())
                .containsExactly(0);
    }

    @Test
    void aPipeInsideTheSentenceOrAnOverlongSentenceIsRejected() {
        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(SECTION,
                "2|T|Der Techniker|prüft.\n2|T|" + "Pumpe ".repeat(300) + "\n");
        assertThat(parsed.sentences().get(1)).isEmpty();
        assertThat(parsed.rejected()).isEqualTo(2);
    }
}
