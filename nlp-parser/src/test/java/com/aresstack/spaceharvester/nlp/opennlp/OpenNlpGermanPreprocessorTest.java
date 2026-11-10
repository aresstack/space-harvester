package com.aresstack.spaceharvester.nlp.opennlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.syntax.MultiwordToken;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Vorhergesagte Vorverarbeitung (keine Gold-Wortarten) mit den OpenNLP-Modellen aus Maven Central. */
class OpenNlpGermanPreprocessorTest {

    private static final OpenNlpGermanPreprocessor PREPROCESSOR = OpenNlpGermanPreprocessor.fromClasspath();

    @Test
    void splitsSentencesWordsAndContractionsWithOriginalSpans() {
        String text = "Der Sachbearbeiter übermittelt dem Vorgesetzten am Montag den Beleg des Kunden. Er schläft.";
        List<Sentence> sentences = PREPROCESSOR.analyze(text);

        assertEquals(2, sentences.size());
        Sentence first = sentences.get(0);
        assertEquals(13, first.tokens().size());
        MultiwordToken am = first.multiwordTokens().get(0);
        assertEquals("am", am.form());
        assertEquals("an", first.token(am.first()).form());
        assertEquals("dem", first.token(am.last()).form());
        for (Token token : first.tokens()) {
            String span = text.substring(token.start(), token.end());
            assertTrue(span.equals(token.form()) || span.equals("am"), span + " / " + token.form());
            assertNotNull(token.upos(), "jedes Wort hat eine Wortart");
            assertTrue(token.feats().isEmpty(), "OpenNLP liefert keine Kasusmerkmale");
            assertEquals(Token.NO_HEAD, token.head());
        }
        assertEquals(List.of("DET", "NOUN", "VERB"), first.tokens().subList(0, 3).stream().map(Token::upos).toList());
        assertEquals("übermitteln", first.token(3).lemma());
        assertEquals("Er schläft.", sentences.get(1).text());
        assertEquals(text.indexOf("Er schläft."), sentences.get(1).start());
    }

    @Test
    void aLineBreakIsAHardSentenceBoundary() {
        // Ursache A (DATENH): ohne Grenze verschmilzt die Überschrift mit dem Folgesatz und wird zum Handelnden
        String text = "Kurzbeschreibung\nDas Programm steht im Mittelpunkt.\n\nKomponenten\nMarktplatz: bildet die Schnittstelle.";
        List<Sentence> sentences = PREPROCESSOR.analyze(text);

        assertEquals(List.of("Kurzbeschreibung", "Das Programm steht im Mittelpunkt.", "Komponenten",
                "Marktplatz: bildet die Schnittstelle."), sentences.stream().map(Sentence::text).toList());
        for (Sentence sentence : sentences) {
            assertEquals(sentence.text(), text.substring(sentence.start(), sentence.end()));
            assertEquals(sentences.indexOf(sentence), sentence.index());
            for (Token token : sentence.tokens()) {
                assertTrue(token.start() >= sentence.start() && token.end() <= sentence.end(), "Spannen im Gesamttext");
            }
        }
        assertEquals(List.of(), PREPROCESSOR.analyze(""));
        assertEquals(1, PREPROCESSOR.analyze("Er schläft.\n").size());
    }

    @Test
    void closedClassWordsAreNeverTaggedAsVerbs() {
        // Ursachen B und G (DATENH): OpenNLP taggt „welchen“ und „hinweg“ hier als VERB
        Sentence relative = PREPROCESSOR.analyze("Datenprodukt-API: Erlaubt den Abruf von Datenprodukten, welchen das "
                + "Interaktionsmuster API zugewiesen ist, über eine Rest-API.").get(0);
        assertEquals("PRON", word(relative, "welchen").upos());
        Sentence postposition = PREPROCESSOR.analyze(
                "Über die einzelnen Vorhaben hinweg ist das Datenhaus die zentrale Plattform.").get(0);
        assertEquals("ADP", word(postposition, "hinweg").upos());
        assertEquals("AUX", word(postposition, "ist").upos());
    }

    @Test
    void separableZuInfinitivesGetTheirInfinitiveAsLemma() {
        // Ursache K (DATENH): der Lemmatisierer lässt „bereitzustellen“ unverändert
        Sentence sentence = PREPROCESSOR.analyze("Das Vorhaben hat sich die Vision gesetzt, die Daten der "
                + "Haushaltsabteilung zentral bereitzustellen.").get(0);
        assertEquals("bereitstellen", word(sentence, "bereitzustellen").lemma());
    }

    private static Token word(Sentence sentence, String form) {
        return sentence.tokens().stream().filter(t -> t.form().equals(form)).findFirst().orElseThrow();
    }

    @Test
    void namesItsModelsWithChecksums() {
        assertEquals(List.of("sentences", "tokens", "upos", "lemma"),
                PREPROCESSOR.components().stream().map(c -> c.layer()).toList());
        assertTrue(PREPROCESSOR.components().stream().allMatch(c -> c.modelSha256().length() == 64));
    }
}
