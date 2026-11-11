package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ursachenmatrix der Aussagenqualität am DATENH-Text (#499, Nachtrag zu ADR 0024). Grundlage ist {@code datenh.conllu},
 * die unveränderte Ausgabe der gepinnten Kette OpenNLP + MaltParser für {@code datenh.txt}; {@code RealModelSmokeTest}
 * prüft, dass die echte Kette genau diese Analyse liefert.
 *
 * <p>Jeder Fall prüft zwei Dinge getrennt: den <b>Zwischenstand</b> (Satzgrenze, Wortart, Grundform oder Kante im
 * CoNLL-U), der die Stufe der Ursache festlegt, und das <b>Endergebnis</b> der Extraktion. Ist der Zwischenstand
 * falsch, ist es ein Fehler der Vorverarbeitung bzw. des Modells; ist er brauchbar und das Ergebnis falsch, ist es
 * ein Regelfehler. Für noch offene Fälle hält der Test den Ist-Zustand fest ({@code IST}); der PR, der die Ursache
 * an ihrer Stufe behebt, kehrt diese Prüfung in eine verbotene Relation um.
 */
class DatenhRegressionTest {

    private static final ParsedDocument DOCUMENT = Gold.load("datenh.conllu");
    private static final ObjectCentricAnalysis ANALYSIS = Gold.analyze("datenh.conllu");

    // ---- Erwartete Relationen, die heute stimmen und stimmen bleiben müssen -------------------------------------

    @Test
    void expectedRelationsHold() {
        List<String> relations = relations();
        for (String expected : List.of(
                "zusammenführen ACTOR Das Vorhaben",
                "zusammenführen AFFECTED_OBJECT Daten",
                "umsetzen AFFECTED_OBJECT datenproduktspezifische Freigaben",
                "bilden ACTOR Marktplatz",
                "bilden AFFECTED_OBJECT die zentrale Anwenderschnittstelle",
                "ermöglichen ACTOR Marktplatz",
                "ermöglichen AFFECTED_OBJECT den selbstständigen Zugriff",
                "erweitern ACTOR Register",
                "erweitern AFFECTED_OBJECT Datenprodukte",
                "verleihen ACTOR Register",
                "verleihen AFFECTED_OBJECT die Fähigkeit",
                "verleihen RECIPIENT dem Datenhaus",
                "ermöglichen ACTOR Das Datenhaus",
                "ermöglichen AFFECTED_OBJECT eine übergreifende Auswertung")) {
            assertTrue(relations.contains(expected), expected + " fehlt in " + relations);
        }
    }

    // ---- C: Präposition als Partikel (MaltParser), behoben in der Extraktion (#563) -------------------------------

    @Test
    void causeC_prepositionAsParticle_isAModelErrorThatTheRuleNoLongerFollows() {
        Sentence sentence = sentence("Marktplatz:");
        Token auf = token(sentence, "auf");
        assertEquals("compound:prt", auf.deprel(), "Zwischenstand: MaltParser hängt „auf“ als Partikel an");
        assertEquals("ermöglicht", sentence.head(auf).form());

        assertTrue(ANALYSIS.statements().stream().noneMatch(s -> s.predicate().lemma().contains("aufermöglich")),
                "verboten: aufermöglichen");
        assertEquals("ermöglicht", statement("ermöglichen", "Marktplatz:").predicate().text());
    }

    // ---- A: Überschrift verschmolz mit dem Folgesatz (Satzgrenzen vor OpenNLP, behoben) ------------------------------

    @Test
    void causeA_aHeadingIsItsOwnSentence() {
        // Zwischenstand: der Zeilenumbruch nach „Kurzbeschreibung“ und „Komponenten“ ist eine harte Satzgrenze
        assertEquals("Kurzbeschreibung", sentence("Kurzbeschreibung").text());
        assertEquals("Komponenten", sentence("Komponenten").text());
        assertEquals(13, DOCUMENT.sentences().size(), "13 Zeilen, 13 Sätze");

        // Endergebnis: die Überschrift ist kein Beteiligter, der eigentliche Handelnde kommt zurück
        List<String> relations = relations();
        assertTrue(relations.stream().noneMatch(r -> r.contains("Kurzbeschreibung") || r.contains("Komponenten B")),
                "verboten: Überschrift als Beteiligter " + relations);
        assertTrue(relations.contains("stehen ACTOR Das Programm"), relations.toString());
        assertTrue(relations.contains("umsetzen ACTOR Berechtigungsmanagement"), relations.toString());
    }

    // ---- B: „welchen“ als Verb (OpenNLP-Wortart, behoben vor dem Parser) ---------------------------------------------

    @Test
    void causeB_relativePronounIsNoLongerAVerb() {
        Sentence sentence = sentence("Datenprodukt-API:");
        Token welchen = token(sentence, "welchen");
        assertEquals("PRON", welchen.upos(), "Zwischenstand: Wortart nach der Korrektur geschlossener Klassen");
        assertEquals("acl:relcl", sentence.head(welchen).deprel(), "MaltParser bildet jetzt einen Relativsatz");

        // Endergebnis: keine Aussage „welchen“; das Pronomen bleibt offen und unsicher
        assertTrue(ANALYSIS.statements().stream().noneMatch(s -> s.predicate().lemma().equals("welcher")),
                "verboten: Prädikat welchen");
        Statement assigned = statement("zuweisen", "Datenprodukt-API:");
        assertTrue(assigned.has(Finding.UNRESOLVED_PRONOUN));
    }

    // ---- G: „hinweg“ als Verb (OpenNLP-Wortart, behoben vor dem Parser) ----------------------------------------------

    @Test
    void causeG_postpositionIsNoLongerAVerb() {
        Sentence sentence = sentence("Über die einzelnen Vorhaben hinweg");
        Token hinweg = token(sentence, "hinweg");
        assertEquals("ADP", hinweg.upos(), "Zwischenstand: Wortart nach der Korrektur geschlossener Klassen");
        assertEquals("case", hinweg.deprel());
        assertEquals("cop", token(sentence, "ist").deprel(), "„ist“ ist Kopula, kein Passivhilfsverb");

        // Endergebnis: keine Passivaussage „hinweg ist“
        assertTrue(ANALYSIS.statements().stream().noneMatch(s -> s.predicate().lemma().equals("hinweg")),
                "verboten: Prädikat hinweg");
    }

    // ---- K: Grundform des zu-Infinitivs (OpenNLP-Grundform, behoben nach dem Lemmatisierer) --------------------------

    @Test
    void causeK_zuInfinitiveGetsItsInfinitive() {
        Sentence sentence = sentence("Das Vorhaben Datenhaus hat sich");
        assertEquals("bereitstellen", token(sentence, "bereitzustellen").lemma(), "Zwischenstand: Grundform korrigiert");
        assertEquals("bereitzustellen", statement("bereitstellen", "Das Vorhaben Datenhaus hat sich").predicate().text());
    }

    // ---- D: „Daten“ als Zeitangabe (OpenNLP-Grundform + Lexikonregel, behoben in der Zeitregel) ----------------------

    @Test
    void causeD_dataIsNoTimeEvenWithTheLemmaDatum() {
        Sentence sentence = sentence("Das Datenhaus ermöglicht");
        Token daten = token(sentence, "Daten");
        assertEquals("datum", daten.lemma(), "Zwischenstand: OpenNLP führt „Daten“ weiter auf „Datum“ zurück");
        assertEquals("obl", daten.deprel(), "die Kante selbst ist brauchbar");

        // Endergebnis: keine Zeitangabe „den Daten“ (Regelfehler behoben), „mit“ macht sie zum Mittel
        Statement statement = statement("ermöglichen", "Das Datenhaus ermöglicht");
        assertTrue(statement.withRole(Role.TIME).isEmpty(), "verboten: TIME den Daten");
        assertTrue(relations().stream().noneMatch(r -> r.contains(" TIME ") && r.contains("Daten")));
        assertEquals("den Daten", text(statement.withRole(Role.INSTRUMENT).get(0)));
    }

    // ---- E: Passiv im gereihten Relativsatz (MaltParser + Stimmenregel) ---------------------------------------------

    @Test
    void causeE_sharedPassiveAuxiliary_isAttachedToTheLastConjunctOnly() {
        Sentence sentence = sentence("Das Vorhaben Datenhaus führt");
        Token isoliert = token(sentence, "isoliert");
        Token ausgewertet = token(sentence, "ausgewertet");
        assertEquals("nsubj", token(sentence, "die").deprel(), "Zwischenstand: Relativpronomen als aktives Subjekt");
        assertEquals("DET", token(sentence, "die").upos(), "und als Artikel getaggt, daher nicht als Pronomen erkannt");
        assertTrue(sentence.dependents(isoliert.id(), "aux").isEmpty(), "„isoliert“ ohne eigenes Hilfsverb");
        assertEquals("aux:pass", sentence.dependents(ausgewertet.id()).stream()
                .filter(t -> t.form().equals("werden")).findFirst().orElseThrow().deprel());

        // IST: dasselbe Pronomen ist im aktiven Glied Handelnder und im passiven Glied Betroffenes
        Statement active = statement("isolieren", "Das Vorhaben Datenhaus führt");
        Statement passive = statement("auswerten", "Das Vorhaben Datenhaus führt");
        assertEquals(Statement.Voice.ACTIVE, active.voice());
        assertEquals("die", text(active.core(Role.ACTOR)));
        assertEquals(Statement.Voice.PASSIVE, passive.voice());
        assertEquals("die", text(passive.core(Role.AFFECTED_OBJECT)));
        assertEquals(Basis.COORDINATION, passive.core(Role.AFFECTED_OBJECT).basis());
        assertFalse(active.has(Finding.UNRESOLVED_PRONOUN), "IST: offenes Pronomen nicht erkannt");
    }

    // ---- E: Hilfsverb jenseits der Teilsatzgrenze und Übernahme in den Relativsatz (behoben in der Extraktion) ------

    @Test
    void causeE_passiveInARelativeClauseNeitherMakesTheMainClausePassiveNorInheritsItsSubject() {
        Sentence sentence = sentence("Das Vorhaben Datenhaus stellt");
        Token stellt = token(sentence, "stellt");
        Token werden = token(sentence, "werden");
        Token ausgewertet = token(sentence, "ausgewertet");
        assertEquals(stellt.id(), werden.head(), "Zwischenstand: MaltParser hängt „werden“ an das Hauptverb");
        assertEquals("aux:pass", werden.deprel());
        assertEquals(stellt.id(), ausgewertet.head(), "und „ausgewertet“ als gereihtes Verb des Hauptsatzes");
        assertEquals("conj", ausgewertet.deprel());

        // Endergebnis: Hauptsatz aktiv mit seinem Handelnden, „ausgewertet werden“ passiv ohne geerbten Handelnden
        Statement main = statement("bereitstellen", "Das Vorhaben Datenhaus stellt");
        assertEquals(Statement.Voice.ACTIVE, main.voice());
        assertEquals("Das Vorhaben", text(main.core(Role.ACTOR)));
        assertEquals("stellt … bereit", main.predicate().text(), "ohne das fremde „werden“");
        assertTrue(main.has(Finding.AUXILIARY_OUTSIDE_CLAUSE));
        Statement passive = statement("auswerten", "Das Vorhaben Datenhaus stellt");
        assertEquals(Statement.Voice.PASSIVE, passive.voice());
        assertEquals("ausgewertet werden", passive.predicate().text());
        assertTrue(passive.arguments().stream().noneMatch(a -> text(a).contains("Vorhaben")),
                "verboten: auswerten mit dem Vorhaben als Beteiligtem");
        assertTrue(passive.has(Finding.COORDINATION_ACROSS_CLAUSE));
    }

    // ---- F: Reflexivpronomen als Objekt (MaltParser + Objektregel, behoben in der Objektregel) -----------------------

    @Test
    void causeF_reflexivePronounIsNoParticipant() {
        Sentence sentence = sentence("Das Vorhaben Datenhaus hat sich");
        assertEquals("obj", token(sentence, "sich").deprel(), "Zwischenstand: „sich“ weiter als Akkusativobjekt");
        assertEquals("nsubj", token(sentence, "Vision").deprel(), "und das eigentliche Objekt als zweites Subjekt");

        // Endergebnis: „sich“ ist kein Beteiligter; „die Vision“ ist unsicheres Objekt aus einer Heuristik
        Statement statement = statement("setzen", "Das Vorhaben Datenhaus hat sich");
        assertTrue(relations().stream().noneMatch(r -> r.endsWith(" sich")), "verboten: sich als Beteiligter");
        assertTrue(SalienceProjection.project(ANALYSIS).core().stream()
                .noneMatch(core -> "sich".equals(core.objectText())), "verboten: Kern … → setzen → sich");
        assertTrue(statement.has(Finding.REFLEXIVE));
        Argument vision = statement.core(Role.AFFECTED_OBJECT);
        assertEquals("die Vision", text(vision));
        assertEquals(Basis.HEURISTIC, vision.basis());
        assertFalse(vision.certain());
    }

    // ---- H: Verbzusatz als prädikatives Adjektiv (MaltParser, behoben in der Prädikatsregel) -------------------------

    @Test
    void causeH_predicativeParticleJoinsThePredicate() {
        Sentence sentence = sentence("Berechtigungsmanagement:");
        Token sicher = token(sentence, "sicher");
        assertEquals("xcomp", sicher.deprel(), "Zwischenstand: „sicher“ (sicherstellen) weiter als xcomp");
        assertEquals("nsubj", token(sentence, "Einhaltung").deprel(), "das Objekt hängt als Subjekt an „sicher“");

        // Endergebnis: „stellt … sicher (sicherstellen)“ mit unsicherem Objekt aus der Heuristik
        Statement statement = statement("sicherstellen", "Berechtigungsmanagement:");
        assertEquals("stellt … sicher", statement.predicate().text());
        assertTrue(statement.has(Finding.PREDICATIVE_PARTICLE));
        Argument object = statement.core(Role.AFFECTED_OBJECT);
        assertEquals("die Einhaltung", text(object));
        assertEquals(Basis.HEURISTIC, object.basis());
        assertFalse(object.certain());
        assertTrue(ANALYSIS.statements().stream().noneMatch(s -> s.predicate().lemma().equals("stellen")),
                "verboten: „stellen“ ohne Verbzusatz");
    }

    // ---- I: Listenkennung als Subjekt (Struktur + MaltParser) -------------------------------------------------------

    @Test
    void causeI_listLabelWithParserEdgeIsEvidenceImplicitLabelOnlyAHeuristic() {
        Sentence sentence = sentence("Register:");
        assertEquals("nsubj", sentence.token(1).deprel(), "Zwischenstand: Kennung „Register:“ als Subjekt");
        assertEquals("obj", sentence.token(3).deprel(), "die Wiederholung „Register“ als Objekt");

        // Endergebnis: die Wiederholung ist kein Objekt mehr; Register → erweitern → Datenprodukte aus Parserkanten
        assertFalse(relations().contains("erweitern AFFECTED_OBJECT Register"), "verboten: Register → erweitern → Register");
        Statement extend = statement("erweitern", "Register:");
        assertTrue(extend.has(Finding.LABEL_REPEATED));
        assertEquals("Datenprodukte", text(extend.core(Role.AFFECTED_OBJECT)));

        // Kennung mit Subjektkante des Parsers bleibt Parserevidenz (Entscheidung zum Nachtrag ADR 0024)
        for (String prefix : List.of("Berechtigungsmanagement:", "Marktplatz:", "Register:")) {
            Statement first = ANALYSIS.statements().stream()
                    .filter(s -> s.sentence() == sentence(prefix).index()).findFirst().orElseThrow();
            assertEquals(Basis.PARSER, first.core(Role.ACTOR).basis(), prefix);
            assertTrue(first.core(Role.ACTOR).certain(), prefix);
            assertFalse(first.has(Finding.SUBJECT_FROM_LABEL), prefix);
        }
        // Listeneintrag ohne Subjektkante: Kennung als impliziter, unsicherer Handelnder
        Statement catalog = statement("bereitstellen", "Datenkatalog:");
        assertEquals("Datenkatalog", text(catalog.core(Role.ACTOR)));
        assertEquals(Basis.HEURISTIC, catalog.core(Role.ACTOR).basis());
        assertFalse(catalog.core(Role.ACTOR).certain());
        assertTrue(catalog.has(Finding.SUBJECT_FROM_LABEL));
        assertEquals("Metadaten", text(catalog.core(Role.AFFECTED_OBJECT)));
    }

    // ---- Qualitätsstufe: nur gestützte Relationen sind PLAUSIBLE ------------------------------------------------------

    @Test
    void onlyWellSupportedRelationsArePlausible() {
        List<String> plausible = new java.util.ArrayList<>();
        for (Statement statement : ANALYSIS.statements()) {
            StatementQuality.Assessment assessment = StatementQuality.assess(ANALYSIS, statement);
            if (assessment.level() == StatementQuality.Level.PLAUSIBLE) {
                assertTrue(assessment.reasons().isEmpty());
                plausible.add(statement.predicate().lemma() + ": " + statement.arguments().stream()
                        .filter(a -> a.priority() == Priority.CORE).map(a -> a.role() + " " + text(a)).toList());
            } else {
                assertFalse(assessment.reasons().isEmpty(), "jede Abstufung nennt ihre Gründe");
            }
        }
        assertEquals(List.of(
                "stehen: [ACTOR Das Programm]",
                "zusammenführen: [ACTOR Das Vorhaben, AFFECTED_OBJECT Daten]",
                "ermöglichen: [ACTOR Das Datenhaus, AFFECTED_OBJECT eine übergreifende Auswertung]",
                "umsetzen: [ACTOR Berechtigungsmanagement, AFFECTED_OBJECT datenproduktspezifische Freigaben]",
                "bilden: [ACTOR Marktplatz, AFFECTED_OBJECT die zentrale Anwenderschnittstelle]",
                "ermöglichen: [ACTOR Marktplatz, AFFECTED_OBJECT den selbstständigen Zugriff]",
                "erweitern: [ACTOR Register, AFFECTED_OBJECT Datenprodukte]",
                "verleihen: [ACTOR Register, AFFECTED_OBJECT die Fähigkeit]"), plausible);

        // Aussagen ohne Beteiligte sind keine Relation, Heuristiken und offene Pronomen nur Kandidaten
        assertEquals(StatementQuality.Level.INVALID, level("verwalten", "Das Vorhaben Datenhaus führt"));
        assertEquals(StatementQuality.Level.UNCERTAIN, level("setzen", "Das Vorhaben Datenhaus hat sich"));
        assertEquals(StatementQuality.Level.UNCERTAIN, level("sicherstellen", "Berechtigungsmanagement:"));
        assertEquals(StatementQuality.Level.UNCERTAIN, level("bereitstellen", "Datenkatalog:"));
        assertEquals(StatementQuality.Level.UNCERTAIN, level("zuweisen", "Datenprodukt-API:"));
        assertEquals(StatementQuality.Level.UNCERTAIN, level("isolieren", "Das Vorhaben Datenhaus führt"));
    }

    private static StatementQuality.Level level(String lemma, String sentencePrefix) {
        return StatementQuality.assess(ANALYSIS, statement(lemma, sentencePrefix)).level();
    }

    // ---- Hilfen -----------------------------------------------------------------------------------------------------

    /** Alle Beteiligungen als {@code "<Prädikatlemma> <Rolle> <Wortlaut>"}. */
    private static List<String> relations() {
        return ANALYSIS.statements().stream().flatMap(statement -> statement.arguments().stream()
                .map(argument -> statement.predicate().lemma() + " " + argument.role() + " " + text(argument)))
                .toList();
    }

    private static String text(Argument argument) {
        return Gold.text(ANALYSIS, argument);
    }

    private static Sentence sentence(String prefix) {
        return DOCUMENT.sentences().stream().filter(s -> s.text().startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("kein Satz beginnt mit " + prefix));
    }

    private static Token token(Sentence sentence, String form) {
        return sentence.tokens().stream().filter(t -> t.form().equals(form)).findFirst()
                .orElseThrow(() -> new AssertionError(form + " fehlt in " + sentence.text()));
    }

    private static Statement statement(String lemma, String sentencePrefix) {
        int index = sentence(sentencePrefix).index();
        return ANALYSIS.statements().stream()
                .filter(s -> s.sentence() == index && s.predicate().lemma().equals(lemma)).findFirst()
                .orElseThrow(() -> new AssertionError("keine Aussage " + lemma + " in Satz " + index));
    }
}
