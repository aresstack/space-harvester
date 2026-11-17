package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regeln aus ADR 0032 (FL1) einzeln an erfundenen Gold-Analysen, jede mit Gegenprobe. Der Prüfstand zeigt nur Summen;
 * hier steht, welche Regel was bewirkt.
 */
class Fl1RulesTest {

    private static final ObjectCentricAnalysis ANALYSIS = Gold.analyze("rules-fl1.conllu");

    private static Statement statement(int sentence, String lemma) {
        return ANALYSIS.statements().stream()
                .filter(s -> s.sentence() == sentence - 1 && lemma.equals(s.predicate().lemma()))
                .findFirst().orElseThrow(() -> new AssertionError("keine Aussage " + lemma + " in Satz " + sentence
                        + ": " + ANALYSIS.statements()));
    }

    private static List<String> heads(Statement statement, Role role) {
        return statement.withRole(role).stream().map(a -> ANALYSIS.mention(a.mention()).headForm()).toList();
    }

    @Test
    void durchInThePassiveWithoutAgentIsAnUncertainDerivedActor() {
        Statement passive = statement(1, "prüfen");
        assertEquals(List.of("Bibliothekarin"), heads(passive, Role.ACTOR));
        Argument actor = passive.withRole(Role.ACTOR).get(0);
        assertEquals(Basis.HEURISTIC, actor.basis());
        assertFalse(actor.certain());
        assertTrue(passive.has(Finding.AGENT_FROM_DURCH));

        Statement active = statement(2, "prüfen");
        assertEquals(List.of("Bibliothekarin"), heads(active, Role.ACTOR), "Gegenprobe Aktiv: durch ist Mittel");
        assertFalse(active.has(Finding.AGENT_FROM_DURCH));

        Statement withVon = statement(3, "prüfen");
        assertEquals(List.of("Leiterin"), heads(withVon, Role.ACTOR), "Gegenprobe: von-Gruppe ist der Handelnde");
        assertFalse(withVon.has(Finding.AGENT_FROM_DURCH));
    }

    @Test
    void vonWithSeinAndZuInfinitiveIsADerivedActorButNotWithWerden() {
        Statement duty = statement(4, "zurückgeben");
        assertEquals(List.of("Leser"), heads(duty, Role.ACTOR));
        assertTrue(duty.has(Finding.AGENT_FROM_VON));

        Statement taken = statement(5, "nehmen");
        assertTrue(heads(taken, Role.ACTOR).isEmpty(), "Gegenprobe: „von dem Regal“ ist Herkunft");
        assertFalse(taken.has(Finding.AGENT_FROM_VON));
    }

    @Test
    void aPureVerbSeriesSharesItsObjectButNotAcrossASubordinateClause() {
        Statement lend = statement(6, "leihen");
        assertEquals(List.of("Buch"), heads(lend, Role.AFFECTED_OBJECT));
        assertTrue(lend.has(Finding.OBJECT_FROM_COORDINATION));

        Statement arrive = statement(7, "eintreffen");
        assertNull(arrive.core(Role.AFFECTED_OBJECT), "Gegenprobe: Verb mit Einleitewort teilt kein Objekt");
        assertFalse(arrive.has(Finding.OBJECT_FROM_COORDINATION));
    }

    @Test
    void aZuInfinitiveWithoutAuxiliaryHasNoSubjectOfItsOwn() {
        Statement giveBack = statement(8, "zurückgeben");
        assertFalse(heads(giveBack, Role.ACTOR).contains("Buch"), "das Nomen ist nicht Handelnder");
        assertEquals(List.of("Buch"), heads(giveBack, Role.AFFECTED_OBJECT));
        assertEquals(Basis.HEURISTIC, giveBack.core(Role.AFFECTED_OBJECT).basis());

        Statement duty = statement(4, "zurückgeben");
        assertFalse(duty.has(Finding.SUBJECT_OF_INFINITIVE), "Gegenprobe: mit Hilfsverb bleibt das Subjekt");
    }

    @Test
    void multiPartNamesOnlyWithHyphenOrFlat() {
        assertEquals(List.of("Online-Katalog"), heads(statement(9, "zeigen"), Role.ACTOR));
        assertEquals(List.of("Load Balancer"), heads(statement(10, "verteilen"), Role.ACTOR));
        assertEquals(List.of("Ausleihe"), heads(statement(11, "prüfen"), Role.ACTOR),
                "Gegenprobe: compound ohne Bindestrich bleibt getrennt");
    }

    @Test
    void aClauseNextToANominalSubjectIsTheObjectAndARelativePronounIsResolved() {
        Statement report = statement(12, "melden");
        assertEquals(List.of("Bibliothek"), heads(report, Role.ACTOR));
        Argument object = report.core(Role.AFFECTED_OBJECT);
        assertEquals(Mention.Kind.CLAUSE, ANALYSIS.mention(object.mention()).kind());
        assertTrue(report.has(Finding.CLAUSE_PARTICIPANT));

        Statement lend = statement(13, "leihen");
        assertEquals(List.of("Leser"), heads(lend, Role.ACTOR));
        assertTrue(lend.has(Finding.RELATIVE_PRONOUN_RESOLVED));
    }

    @Test
    void aVerbSecondBehindAnInsertedClauseTakesTheSubjectOfTheMainClause() {
        assertEquals(List.of("Leserin"), heads(statement(14, "informieren"), Role.ACTOR),
                "Zweitstellung: gereiht mit „zahlt“, nicht mit dem sobald-Satz");
        assertEquals(List.of("Mahnung"), heads(statement(15, "vorliegen"), Role.ACTOR),
                "Gegenprobe: Verbletzt im Nebensatz bleibt beim Subjekt des Nebensatzes");
    }
}
