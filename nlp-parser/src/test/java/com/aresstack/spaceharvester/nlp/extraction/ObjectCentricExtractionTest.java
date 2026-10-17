package com.aresstack.spaceharvester.nlp.extraction;

import static com.aresstack.spaceharvester.nlp.extraction.Gold.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.extraction.Statement.Clause;
import com.aresstack.spaceharvester.nlp.extraction.Statement.Voice;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regeln der objektzentrierten Auswertung an handgeschriebenen Gold-Analysen (Akzeptanzkriterien aus #98). */
class ObjectCentricExtractionTest {

    @Test
    void referenceSentenceYieldsCoreRelationWithSeparateRecipientTimeAndGenitive() {
        ObjectCentricAnalysis analysis = Gold.analyze("reference.conllu");

        assertEquals(1, analysis.statements().size());
        Statement statement = analysis.statements().get(0);
        assertEquals("test/gold@1#s1.v3", statement.id());
        assertEquals("übermittelt", statement.predicate().text());
        assertEquals("übermitteln", statement.predicate().lemma());
        assertEquals(Voice.ACTIVE, statement.voice());
        assertEquals(Clause.MAIN, statement.clause());
        assertEquals("Der Sachbearbeiter", text(analysis, statement.core(Role.ACTOR)));
        assertEquals("den Beleg", text(analysis, statement.core(Role.AFFECTED_OBJECT)));

        Argument recipient = statement.withRole(Role.RECIPIENT).get(0);
        assertEquals("dem Vorgesetzten", text(analysis, recipient));
        assertEquals("obl:arg", recipient.relation());
        assertEquals(Priority.SUPPLEMENTARY, recipient.priority());
        assertTrue(recipient.certain(), "dem bestätigt den Dativ");

        Argument time = statement.withRole(Role.TIME).get(0);
        assertEquals("Montag", text(analysis, time));
        assertEquals("am", time.marker());
        assertEquals(Priority.CONTEXT, time.priority());
        assertEquals(Basis.LEXICON, time.basis());

        // Kunde ist kein Beteiligter der Übermittlung, sondern nominaler Bezug des Belegs, ohne Besitzaussage
        assertTrue(statement.arguments().stream().noneMatch(a -> text(analysis, a).contains("Kunden")));
        Mention beleg = analysis.mention(statement.core(Role.AFFECTED_OBJECT).mention());
        List<NominalAttachment> attachments = analysis.attachmentsOf(beleg.id());
        assertEquals(1, attachments.size());
        assertEquals("des Kunden", analysis.mention(attachments.get(0).dependent()).text());
        assertEquals(NominalAttachment.Kind.GENITIVE, attachments.get(0).kind());
        assertEquals(Basis.CASE_HEURISTIC, attachments.get(0).basis(), "Genitiv nur aus dem Artikel des");

        assertTrue(statement.certain());
        assertTrue(statement.findings().isEmpty(), statement.findings().toString());
    }

    @Test
    void mentionsKeepOriginalSpansAndLemmas() {
        ObjectCentricAnalysis analysis = Gold.analyze("reference.conllu");
        Mention subject = analysis.mention("test/gold@1#s1.t2");
        String text = analysis.document().text();

        assertEquals("Der Sachbearbeiter", text.substring(subject.start(), subject.end()));
        assertEquals(List.of(1, 2), subject.tokens());
        assertEquals("Sachbearbeiter", subject.lemma());
        assertEquals(Resolution.SURFACE, subject.resolution());
        Mention time = analysis.mention("test/gold@1#s1.t8");
        assertEquals("Montag", time.text(), "Präposition und verschmolzener Artikel gehören nicht zur Gruppe");
    }

    @Test
    void passiveSubjectIsAffectedAndAgentActsButNotInCore() {
        ObjectCentricAnalysis analysis = Gold.analyze("passive.conllu");
        Statement statement = analysis.statements().get(0);

        assertEquals(Voice.PASSIVE, statement.voice());
        assertTrue(statement.has(Finding.PASSIVE));
        assertEquals("wird … übermittelt", statement.predicate().text());
        Argument subject = statement.subject();
        assertEquals("nsubj:pass", subject.relation());
        assertEquals(Role.AFFECTED_OBJECT, subject.role());
        assertEquals("Der Beleg", text(analysis, subject));
        Argument agent = statement.withRole(Role.ACTOR).get(0);
        assertEquals("obl:agent", agent.relation());
        assertEquals("vom", agent.marker());
        assertEquals("Sachbearbeiter", text(analysis, agent));
        assertEquals(Role.CIRCUMSTANCE, statement.withRole(Role.CIRCUMSTANCE).get(0).role());
        assertTrue(SalienceProjection.project(analysis).core().isEmpty(), "Passiv ist nachrangig");
    }

    @Test
    void separableVerbsAndCoordinatedPredicateWithInheritedSubject() {
        ObjectCentricAnalysis analysis = Gold.analyze("coordination.conllu");
        assertEquals(2, analysis.statements().size());
        Statement first = analysis.statements().get(0);
        Statement second = analysis.statements().get(1);

        assertEquals("ruft … an", first.predicate().text());
        assertEquals("anrufen", first.predicate().lemma());
        assertEquals("reicht … ein", second.predicate().text());
        assertEquals("einreichen", second.predicate().lemma());

        assertEquals(Clause.COORDINATED, second.clause());
        Argument inherited = second.subject();
        assertEquals("Der Kunde", text(analysis, inherited));
        assertEquals(Basis.COORDINATION, inherited.basis());
        assertFalse(inherited.certain());
        assertTrue(second.has(Finding.SUBJECT_FROM_COORDINATION));
        assertEquals("den Antrag", text(analysis, second.object()));

        List<CoreRelation> core = SalienceProjection.project(analysis).core();
        assertEquals(1, core.size(), "nur das Prädikat mit eigenem Subjekt ist Kern");
        assertEquals("anrufen", core.get(0).predicateLemma());
        assertEquals(List.of(second.id()), SalienceProjection.project(analysis).secondary());
    }

    @Test
    void pronounsStayUnresolved() {
        ObjectCentricAnalysis analysis = Gold.analyze("pronouns.conllu");
        Statement statement = analysis.statements().get(0);

        assertTrue(statement.has(Finding.UNRESOLVED_PRONOUN));
        assertEquals(Resolution.UNRESOLVED, analysis.mention(statement.subject().mention()).resolution());
        assertEquals(Mention.Kind.PRONOUN, analysis.mention(statement.object().mention()).kind());
        CoreRelation core = SalienceProjection.project(analysis).core().get(0);
        assertTrue(core.unresolved());
        assertEquals("Er", core.subjectText());
        assertTrue(MentionStatistics.of(analysis).isEmpty(), "Pronomen zählen nicht als Begriffe");
    }

    @Test
    void dativeArgumentAndTimeAreDistinguishedByRelationNotByCase() {
        ObjectCentricAnalysis analysis = Gold.analyze("dative-time.conllu");
        Statement answers = analysis.statements().get(0);
        Statement works = analysis.statements().get(1);

        assertTrue(answers.has(Finding.NO_ACCUSATIVE_OBJECT));
        assertNull(answers.object());
        assertEquals("dem Kunden", text(analysis, answers.withRole(Role.RECIPIENT).get(0)));

        assertTrue(works.has(Finding.NO_ACCUSATIVE_OBJECT));
        assertTrue(works.withRole(Role.RECIPIENT).isEmpty(), "am Montag ist Dativ, aber kein Empfänger");
        assertEquals("Montag", text(analysis, works.withRole(Role.TIME).get(0)));
        assertTrue(SalienceProjection.project(analysis).core().isEmpty(), "ohne Akkusativobjekt kein Kern");
    }

    @Test
    void subordinateClausesAreRecognisedAndKeptOutOfCore() {
        ObjectCentricAnalysis analysis = Gold.analyze("subordinate.conllu");
        Statement main = analysis.statements().get(0);
        Statement reason = analysis.statements().get(1);

        assertEquals(Voice.PASSIVE, main.voice());
        assertEquals(Clause.SUBORDINATE, reason.clause());
        assertTrue(reason.has(Finding.SUBORDINATE_CLAUSE));
        assertTrue(reason.has(Finding.UNRESOLVED_PRONOUN));
        assertEquals("der Prüfer", text(analysis, reason.subject()));
        assertEquals("sie", text(analysis, reason.object()));
        assertTrue(SalienceProjection.project(analysis).core().isEmpty());
    }

    @Test
    void repeatedWordsStaySeparateMentionsAndQualifiersStayWithTheirStatement() {
        ObjectCentricAnalysis analysis = Gold.analyze("repeated.conllu");
        Statement first = analysis.statements().get(0);
        Statement second = analysis.statements().get(1);

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.subject().mention(), second.subject().mention());
        assertEquals("dem Vorgesetzten", text(analysis, first.withRole(Role.RECIPIENT).get(0)));
        assertEquals("Montag", text(analysis, first.withRole(Role.TIME).get(0)));
        assertEquals("der Kasse", text(analysis, second.withRole(Role.RECIPIENT).get(0)));
        assertEquals("Freitag", text(analysis, second.withRole(Role.TIME).get(0)));
        assertEquals("Post", text(analysis, second.withRole(Role.INSTRUMENT).get(0)));
        assertEquals(1, first.withRole(Role.RECIPIENT).size());

        MentionStatistics.TermCount subjects = MentionStatistics.of(analysis).get(0);
        assertEquals("Sachbearbeiter", subjects.key());
        assertEquals(MentionStatistics.Function.SUBJECT, subjects.function());
        assertEquals(List.of("test/gold@1#s1.t2", "test/gold@1#s2.t2"), subjects.mentions());

        SalienceProjection.Projection projection = SalienceProjection.project(analysis);
        assertEquals(2, projection.core().size());
        CoreRelation core = projection.core().get(1);
        Statement details = analysis.statement(core.statement());
        assertEquals("Der Sachbearbeiter übermittelt der Kasse am Freitag den Antrag per Post.",
                analysis.evidence(details).text());
    }

    @Test
    void caseFallbackProposesMissingObjectAsHeuristicAndFlagsConflicts() {
        ObjectCentricAnalysis analysis = Gold.analyze("case-fallback.conllu");
        Statement proposed = analysis.statements().get(0);
        Statement conflict = analysis.statements().get(1);

        Argument object = proposed.object();
        assertEquals("obj?", object.relation());
        assertEquals("Einen Antrag", text(analysis, object));
        assertEquals(Basis.CASE_HEURISTIC, object.basis());
        assertFalse(object.certain());
        assertTrue(proposed.has(Finding.HEURISTIC_PARTICIPANT));
        assertFalse(proposed.has(Finding.NO_ACCUSATIVE_OBJECT));

        Argument subject = conflict.subject();
        assertEquals("Den Antrag", text(analysis, subject));
        assertEquals("nsubj", subject.relation(), "die Parserkante bleibt");
        assertFalse(subject.certain());
        assertTrue(conflict.has(Finding.CASE_CONFLICT));

        assertTrue(SalienceProjection.project(analysis).core().isEmpty(), "Heuristik und Konflikt sind nie Kern");
        assertTrue(MentionStatistics.of(analysis).stream().noneMatch(count -> count.key().equals("Antrag")
                && count.function() == MentionStatistics.Function.OBJECT), "Heuristik zählt nicht als Parserkante");
    }

    @Test
    void caseFallbackCanBeSwitchedOff() {
        ObjectCentricAnalysis analysis = new ObjectCentricExtraction(new ExtractionSettings(false))
                .analyze(Gold.load("case-fallback.conllu"));
        Statement proposed = analysis.statements().get(0);

        assertNull(proposed.object());
        assertTrue(proposed.has(Finding.NO_ACCUSATIVE_OBJECT));
        assertFalse(analysis.statements().get(1).has(Finding.CASE_CONFLICT));
    }

    @Test
    void realMisparseOfDativeAsObjectIsFlaggedAndNotACoreRelation() {
        ObjectCentricAnalysis analysis = Gold.analyze("misparse.conllu");
        Statement statement = analysis.statements().get(0);

        assertEquals("dem Vorgesetzten", text(analysis, statement.object()));
        assertFalse(statement.object().certain(), "dem ist nie Akkusativ");
        assertTrue(statement.has(Finding.CASE_CONFLICT));
        assertTrue(SalienceProjection.project(analysis).core().isEmpty());
    }

    @Test
    void sharedPassiveCarriesOverToTheCoordinatedPredicate() {
        ObjectCentricAnalysis analysis = Gold.analyze("passive-coordination.conllu");
        Statement second = analysis.statements().get(1);

        assertEquals(Voice.PASSIVE, second.voice());
        assertTrue(second.has(Finding.PASSIVE));
        assertTrue(second.has(Finding.SUBJECT_FROM_COORDINATION));
        assertEquals(Role.AFFECTED_OBJECT, second.subject().role());
        assertEquals("Die Anträge", text(analysis, second.subject()));
    }
}
