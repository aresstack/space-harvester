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
    void namesItsModelsWithChecksums() {
        assertEquals(List.of("sentences", "tokens", "upos", "lemma"),
                PREPROCESSOR.components().stream().map(c -> c.layer()).toList());
        assertTrue(PREPROCESSOR.components().stream().allMatch(c -> c.modelSha256().length() == 64));
    }
}
