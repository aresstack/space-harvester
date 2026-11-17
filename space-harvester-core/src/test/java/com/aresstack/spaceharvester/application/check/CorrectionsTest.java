package com.aresstack.spaceharvester.application.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Eine Korrektur vervollständigt, sie tauscht nichts aus (ADR 0032, FL4); nur erfundene Sätze. */
class CorrectionsTest {

    private static final String SENTENCE = "Die Stelle sendet dem Kunden den Bescheid nicht.";
    private static final Corrections.View CORRECTED = new Corrections.View("senden",
            ParsedStatements.Negation.NEGATED, Set.of("stelle"), List.of("Die Stelle", "den Bescheid"));

    private static Corrections.View candidate(String lemma, ParsedStatements.Negation negation, Set<String> actors,
                                              String... participants) {
        return new Corrections.View(lemma, negation, actors, List.of(participants));
    }

    @Test
    void aCompletionWithTheSameVerbActorAndNegationIsAccepted() {
        assertThat(Corrections.accept(CORRECTED, candidate("senden", ParsedStatements.Negation.NEGATED,
                Set.of("stelle"), "Die Stelle", "dem Kunden", "den Bescheid"), SENTENCE))
                .isEqualTo(Corrections.Outcome.ACCEPTED);
    }

    @Test
    void anythingButACompletionIsRejected() {
        assertThat(Corrections.accept(CORRECTED, null, SENTENCE)).isEqualTo(Corrections.Outcome.OTHER_VERB);
        assertThat(Corrections.accept(CORRECTED, candidate("prüfen", ParsedStatements.Negation.NEGATED,
                Set.of("stelle"), "Die Stelle", "dem Kunden"), SENTENCE)).isEqualTo(Corrections.Outcome.OTHER_VERB);
        assertThat(Corrections.accept(CORRECTED, candidate("senden", ParsedStatements.Negation.AFFIRMED,
                Set.of("stelle"), "Die Stelle", "dem Kunden"), SENTENCE)).as("Verneinung verloren")
                .isEqualTo(Corrections.Outcome.NEGATION);
        assertThat(Corrections.accept(CORRECTED, candidate("senden", ParsedStatements.Negation.NEGATED,
                Set.of("kunde"), "Der Kunde", "den Bescheid"), SENTENCE)).as("anderer Handelnder")
                .isEqualTo(Corrections.Outcome.ACTOR);
        assertThat(Corrections.accept(CORRECTED, candidate("senden", ParsedStatements.Negation.NEGATED,
                Set.of("stelle"), "Die Stelle", "der Behörde"), SENTENCE)).as("nicht im Satz")
                .isEqualTo(Corrections.Outcome.WORDS);
        assertThat(Corrections.accept(CORRECTED, candidate("senden", ParsedStatements.Negation.NEGATED,
                Set.of("stelle"), "die Stelle", "den  Bescheid"), SENTENCE)).as("kein neuer Beteiligter")
                .isEqualTo(Corrections.Outcome.NOTHING_NEW);
    }

    @Test
    void withoutAnActorTheCorrectionMayAddOne() {
        Corrections.View passive = new Corrections.View("senden", ParsedStatements.Negation.UNKNOWN, Set.of(),
                List.of("den Bescheid"));

        assertThat(Corrections.accept(passive, candidate("senden", ParsedStatements.Negation.NEGATED,
                Set.of("stelle"), "Die Stelle", "den Bescheid"), SENTENCE)).isEqualTo(Corrections.Outcome.ACCEPTED);
    }
}
