package com.aresstack.spaceharvester.application.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Eingabe und strenges Lesen des Prüfschritts (ADR 0032, FL4); nur erfundene Sätze, kein Modell. */
class StatementCheckTest {

    private static final String SENTENCE = "Die Stelle sendet dem Kunden den Bescheid nicht.";

    private static StatementCheck.Item sendet() {
        return new StatementCheck.Item("senden", false, List.of(
                new StatementCheck.Participant(ParsedStatements.Role.ACTOR, null, "Die Stelle"),
                new StatementCheck.Participant(ParsedStatements.Role.AFFECTED_OBJECT, null, "den Bescheid")),
                new ParsedStatements.Features(ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.NONE,
                        ParsedStatements.Temporality.GILT));
    }

    @Test
    void theInputNamesTheSentenceAndNumbersTheStatements() {
        StatementCheck.Item state = new StatementCheck.Item("zuständig", true, List.of(
                new StatementCheck.Participant(ParsedStatements.Role.BEARER, null, "Die Stelle"),
                new StatementCheck.Participant(ParsedStatements.Role.PREPOSITIONAL_OBJECT, "für", "den Antrag")),
                ParsedStatements.Features.UNKNOWN);

        String input = StatementCheck.input(SENTENCE, List.of(sendet(), state));

        assertThat(input).isEqualTo("Satz: " + SENTENCE + "\n"
                + "1 Verb: senden; Handelnder: Die Stelle; Betroffenes: den Bescheid; verneint: ja; "
                + "Verbindlichkeit: keine; Zeitbezug: gilt\n"
                + "2 Zustand: zuständig; Träger: Die Stelle; Ergänzung mit „für“: den Antrag\n");
        assertThat(StatementCheck.instructions()).contains("UNVOLLSTAENDIG|<Korrektur>").contains("Material");
    }

    @Test
    void onlyExactLinesForKnownNumbersCount() {
        StatementCheck.Parsed parsed = StatementCheck.parse(SENTENCE, 3, """
                1|STIMMT
                2|FALSCH
                Begründung: passt nicht
                4|STIMMT
                3 | STIMMT
                3|STIMMT|weil
                """);

        assertThat(parsed.answers()).containsOnlyKeys(1, 2);
        assertThat(parsed.answers().get(1).verdict()).isEqualTo(ParsedStatements.Verdict.STIMMT);
        assertThat(parsed.answers().get(2).verdict()).isEqualTo(ParsedStatements.Verdict.FALSCH);
        assertThat(parsed.rejected()).as("Freitext, fremde Nummer, Leerzeichen, Korrektur bei STIMMT").isEqualTo(4);
    }

    @Test
    void twoDifferentVerdictsCancelEachOtherAndARepeatCountsOnce() {
        StatementCheck.Parsed parsed = StatementCheck.parse(SENTENCE, 2, "1|STIMMT\n1|FALSCH\n2|FALSCH\n2|FALSCH\n");

        assertThat(parsed.answers()).containsOnlyKeys(2);
        assertThat(parsed.conflicting()).isEqualTo(1);
    }

    @Test
    void aCorrectionMustUseOnlyWordsOfTheSentence() {
        StatementCheck.Parsed parsed = StatementCheck.parse(SENTENCE, 3, """
                1|UNVOLLSTAENDIG|Die Stelle sendet dem Kunden den Bescheid nicht.
                2|UNVOLLSTAENDIG|Die Stelle sendet dem Kunden heute den Bescheid nicht.
                3|UNVOLLSTAENDIG
                """);

        assertThat(parsed.answers().get(1).correction()).isEqualTo("Die Stelle sendet dem Kunden den Bescheid nicht.");
        assertThat(parsed.answers().get(2).verdict()).as("das Urteil gilt ohne Korrektur")
                .isEqualTo(ParsedStatements.Verdict.UNVOLLSTAENDIG);
        assertThat(parsed.answers().get(2).correction()).as("„heute“ steht nicht im Satz").isNull();
        assertThat(parsed.answers().get(3).correction()).isNull();
        assertThat(parsed.correctionsRejected()).isEqualTo(1);
    }

    @Test
    void withContextTheSectionLinesComeFirstAndCorrectionsMayUseTheirWords() {
        List<String> context = List.of("Die Prüfstelle hat zwei Aufgaben.", "Den Bescheid senden.");
        StatementCheck.Item item = new StatementCheck.Item("senden", false, List.of(
                new StatementCheck.Participant(ParsedStatements.Role.ACTOR, null, "Die Prüfstelle")),
                ParsedStatements.Features.UNKNOWN);

        assertThat(StatementCheck.input("Den Bescheid senden.", context, List.of(item))).isEqualTo("""
                Zusammenhang:
                > Die Prüfstelle hat zwei Aufgaben.
                > Den Bescheid senden.
                Satz: Den Bescheid senden.
                1 Verb: senden; Handelnder: Die Prüfstelle
                """);
        assertThat(StatementCheck.input("Den Bescheid senden.", List.of(), List.of(item)))
                .as("ohne Zusammenhang wie bisher").startsWith("Satz: ");
        String answer = "1|UNVOLLSTAENDIG|Die Prüfstelle sendet den Bescheid.";
        assertThat(StatementCheck.parse("Den Bescheid senden.", context, 1, answer).answers().get(1).correction())
                .isEqualTo("Die Prüfstelle sendet den Bescheid.");
        assertThat(StatementCheck.parse("Den Bescheid senden.", 1, answer).correctionsRejected())
                .as("ohne Zusammenhang fehlt „Prüfstelle“").isEqualTo(1);
        assertThat(StatementCheckStore.key("Den Bescheid senden.", context, "x"))
                .as("der Zusammenhang gehört zum Schlüssel")
                .isNotEqualTo(StatementCheckStore.key("Den Bescheid senden.", "x"));
        assertThat(StatementCheckStore.key("Den Bescheid senden.", List.of(), "x"))
                .isEqualTo(StatementCheckStore.key("Den Bescheid senden.", "x"));
    }

    @Test
    void aCorrectionMayNotAddANegationOrAModalVerb() {
        String sentence = "Die Stelle sendet dem Kunden den Bescheid.";
        StatementCheck.Parsed parsed = StatementCheck.parse(sentence, 2, """
                1|UNVOLLSTAENDIG|Die Stelle sendet dem Kunden den Bescheid nicht.
                2|UNVOLLSTAENDIG|Die Stelle muss dem Kunden den Bescheid senden.
                """);

        assertThat(parsed.answers().values()).allSatisfy(answer -> assertThat(answer.correction()).isNull());
        assertThat(parsed.correctionsRejected()).isEqualTo(2);
    }
}
