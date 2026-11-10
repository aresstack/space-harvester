package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---- A: Überschrift verschmilzt mit dem Folgesatz (Satzgrenzen vor OpenNLP) -----------------------------------

    @Test
    void causeA_headingMergedIntoTheNextSentence_isASentenceBoundaryError() {
        // Zwischenstand: OpenNLP übergeht den Zeilenumbruch nach „Kurzbeschreibung“ und „Komponenten“
        assertTrue(sentence("Kurzbeschreibung").text().startsWith("Kurzbeschreibung Das Programm"));
        assertTrue(sentence("Komponenten").text().startsWith("Komponenten Berechtigungsmanagement:"));
        assertEquals(10, DOCUMENT.sentences().size(), "12 Zeilen, aber nur 10 Sätze");

        // IST (Folgefehler, kein Regelfehler): die Überschrift wird zum Handelnden
        List<String> relations = relations();
        assertTrue(relations.contains("stehen ACTOR Kurzbeschreibung"), relations.toString());
        assertTrue(relations.stream().anyMatch(r -> r.startsWith("umsetzen ACTOR Komponenten")), relations.toString());
    }

    // ---- B: „welchen“ als Verb (OpenNLP-Wortart) -------------------------------------------------------------------

    @Test
    void causeB_relativePronounTaggedAsVerb_isATaggerError() {
        Sentence sentence = sentence("Datenprodukt-API:");
        Token welchen = token(sentence, "welchen");
        assertEquals("VERB", welchen.upos(), "Zwischenstand: OpenNLP taggt das Relativpronomen als Verb");
        assertEquals("root", welchen.deprel(), "MaltParser folgt der Wortart und macht es zur Wurzel");

        // IST (Folgefehler): eine Aussage mit dem Prädikat „welchen“
        assertEquals("welcher", statement("welcher", "Datenprodukt-API:").predicate().lemma());
    }

    // ---- G: „hinweg“ als Verb (OpenNLP-Wortart) --------------------------------------------------------------------

    @Test
    void causeG_postpositionTaggedAsVerb_isATaggerError() {
        Sentence sentence = sentence("Über die einzelnen Vorhaben hinweg");
        Token hinweg = token(sentence, "hinweg");
        assertEquals("VERB", hinweg.upos(), "Zwischenstand: OpenNLP taggt die Postposition als Verb");
        assertEquals("aux:pass", token(sentence, "ist").deprel(), "MaltParser macht „ist“ zum Passivhilfsverb");

        // IST (Folgefehler): Passivaussage „hinweg ist“ mit zwei betroffenen Objekten
        Statement statement = statement("hinweg", "Über die einzelnen Vorhaben hinweg");
        assertEquals(Statement.Voice.PASSIVE, statement.voice());
        assertEquals(2, statement.withRole(Role.AFFECTED_OBJECT).size());
    }

    // ---- D: „Daten“ als Zeitangabe (OpenNLP-Grundform + Lexikonregel) ----------------------------------------------

    @Test
    void causeD_dataReadAsDate_isALemmaErrorThatTheTimeRuleAmplifies() {
        Sentence sentence = sentence("Das Datenhaus ermöglicht");
        Token daten = token(sentence, "Daten");
        assertEquals("datum", daten.lemma(), "Zwischenstand: OpenNLP führt „Daten“ auf „Datum“ zurück");
        assertEquals("obl", daten.deprel(), "die Kante selbst ist brauchbar");

        // IST (Regelfehler auf falscher Grundform): die Zeitregel liest das Lemma „datum“ als Zeitwort
        Argument time = statement("ermöglichen", "Das Datenhaus ermöglicht").withRole(Role.TIME).get(0);
        assertEquals("den Daten", text(time));
        assertEquals(Basis.LEXICON, time.basis());
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

    // ---- F: Reflexivpronomen als Objekt (MaltParser + Objektregel) --------------------------------------------------

    @Test
    void causeF_reflexivePronounAsObject_isAModelErrorThatTheObjectRuleAccepts() {
        Sentence sentence = sentence("Das Vorhaben Datenhaus hat sich");
        assertEquals("obj", token(sentence, "sich").deprel(), "Zwischenstand: „sich“ als Akkusativobjekt");
        assertEquals("nsubj", token(sentence, "Vision").deprel(), "und das eigentliche Objekt als zweites Subjekt");

        // IST (Regelfehler): Kernrelation „Das Vorhaben → setzen → sich“
        Statement statement = statement("setzen", "Das Vorhaben Datenhaus hat sich");
        assertEquals("sich", text(statement.core(Role.AFFECTED_OBJECT)));
        assertTrue(SalienceProjection.project(ANALYSIS).core().stream()
                .anyMatch(core -> "sich".equals(core.objectText())));
        assertTrue(statement.withRole(Role.AFFECTED_OBJECT).stream().noneMatch(a -> text(a).contains("Vision")));
    }

    // ---- H: Verbzusatz als prädikatives Adjektiv (MaltParser) -------------------------------------------------------

    @Test
    void causeH_adjectivalParticleAsXcomp_losesTheObject() {
        Sentence sentence = sentence("Komponenten");
        Token sicher = token(sentence, "sicher");
        assertEquals("xcomp", sicher.deprel(), "Zwischenstand: „sicher“ (sicherstellen) als xcomp, nicht als Partikel");
        assertEquals("nsubj", token(sentence, "Einhaltung").deprel(), "das Objekt hängt als Subjekt an „sicher“");

        // IST: „stellt“ ohne Verbzusatz und ohne Objekt
        Statement statement = statement("stellen", "Komponenten");
        assertEquals("stellt", statement.predicate().text());
        assertNull(statement.core(Role.AFFECTED_OBJECT));
    }

    // ---- I: Listenkennung als Subjekt (Struktur + MaltParser) -------------------------------------------------------

    @Test
    void causeI_listLabelAsSubject_pushesTheRealSubjectIntoTheObject() {
        Sentence sentence = sentence("Register:");
        assertEquals("nsubj", sentence.token(1).deprel(), "Zwischenstand: Kennung „Register:“ als Subjekt");
        assertEquals("obj", sentence.token(3).deprel(), "das eigentliche Subjekt „Register“ als Objekt");

        // IST: Kernrelation „Register → erweitern → Register“
        assertTrue(relations().contains("erweitern AFFECTED_OBJECT Register"));
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
