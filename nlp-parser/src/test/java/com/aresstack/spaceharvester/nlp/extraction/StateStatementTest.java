package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Zustandsaussagen mit Kopula und Träger (ADR 0032) an erfundenen Gold-Analysen. */
class StateStatementTest {

    private static final ObjectCentricAnalysis ANALYSIS = Gold.analyze("zustaende.conllu");

    private static List<Statement> in(int sentence) {
        return ANALYSIS.statements().stream().filter(s -> s.sentence() == sentence).toList();
    }

    private static Statement only(int sentence) {
        List<Statement> statements = in(sentence);
        assertEquals(1, statements.size(), "Satz " + sentence + ": " + statements);
        return statements.get(0);
    }

    @Test
    void anAdjectiveWithCopulaIsAPropertyOfItsBearer() {
        Statement state = only(0);
        assertEquals("defekt", state.predicate().lemma());
        assertEquals("war defekt", state.predicate().text());
        assertEquals("Die Pumpe", Gold.text(ANALYSIS, state.core(Role.BEARER)));
        assertEquals(null, state.core(Role.ACTOR), "ein Träger handelt nie");
        assertTrue(state.has(Finding.STATE_PROPERTY));
        assertEquals(StatementQuality.Level.PLAUSIBLE, StatementQuality.assess(ANALYSIS, state).level());
        StatementFeatures.Features features = StatementFeatures.assess(ANALYSIS, state);
        assertEquals(StatementFeatures.Temporality.FRUEHER, features.temporality(), "„war“ wie bisher");
    }

    @Test
    void aNounWithCopulaIsARoleNoun() {
        Statement state = only(1);
        assertEquals("Ansprechpartner", state.predicate().lemma());
        assertEquals(Set.of(Finding.STATE_ROLE_NOUN), state.findings());
        assertEquals("Der Techniker", Gold.text(ANALYSIS, state.core(Role.BEARER)));
    }

    @Test
    void werdenMarksAChangeOfStateAndBleibenIsNoVerb() {
        assertTrue(only(2).has(Finding.STATE_CHANGE));
        Statement remains = only(3);
        assertEquals("dicht", remains.predicate().lemma(), "„bleibt“ ist Kopula, keine eigene Aussage");
        assertEquals("bleibt dicht", remains.predicate().text());
        assertEquals("Das Ventil", Gold.text(ANALYSIS, remains.core(Role.BEARER)));
    }

    @Test
    void aParticipleTaggedAsAdjectiveIsNoState() {
        assertEquals(List.of(), in(4), "Grundform weicht ab: Partizip, keine Zustandsaussage");
    }

    @Test
    void aPrecedingAdjectiveCarryingTheCopulaLeavesOneStatementOnTheLast() {
        Statement state = only(5);
        assertEquals("korrekt", state.predicate().lemma());
        assertEquals("Der Bericht", Gold.text(ANALYSIS, state.core(Role.BEARER)));
    }

    @Test
    void aSubjectAfterThePredicateNounIsOnlyUncertain() {
        Statement state = only(6);
        assertTrue(state.has(Finding.HEURISTIC_PARTICIPANT));
        assertEquals(StatementQuality.Level.UNCERTAIN, StatementQuality.assess(ANALYSIS, state).level());
    }

    @Test
    void aStateInAContentClauseIsItsOwnStatementTheClauseRefersTo() {
        List<Statement> statements = in(7);
        assertEquals(List.of("melden", "dicht"), statements.stream().map(s -> s.predicate().lemma()).toList());
        Statement state = statements.get(1);
        assertTrue(state.has(Finding.SUBORDINATE_CLAUSE));
        assertEquals(Statement.Clause.SUBORDINATE, state.clause());
        Argument clause = statements.get(0).core(Role.AFFECTED_OBJECT);
        assertEquals(Mention.Kind.CLAUSE, ANALYSIS.mention(clause.mention()).kind());
        assertEquals(state.predicate().verb(), ANALYSIS.mention(clause.mention()).head(),
                "der Teilsatz zeigt auf die Zustandsaussage");
    }
}
