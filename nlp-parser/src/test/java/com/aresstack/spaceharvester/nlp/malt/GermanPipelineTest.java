package com.aresstack.spaceharvester.nlp.malt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.extraction.CoreRelation;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.SalienceProjection;
import com.aresstack.spaceharvester.nlp.opennlp.OpenNlpGermanPreprocessor;
import com.aresstack.spaceharvester.nlp.syntax.ConllU;
import com.aresstack.spaceharvester.nlp.syntax.DependencyAnalysis;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Laufzeitpfad Rohtext → OpenNLP → MaltParser → Auswertung mit dem kleinen Testmodell. Prüft die Verdrahtung, nicht
 * die Parserqualität; die misst #99 am GSD-Modell, den Rauchtest dazu enthält {@link RealModelSmokeTest}.
 */
class GermanPipelineTest {

    private static final DependencyAnalysis ANALYSIS = new DependencyAnalysis(
            OpenNlpGermanPreprocessor.fromClasspath(), TestModel.load());
    private static final String TEXT =
            "Der Sachbearbeiter übermittelt dem Vorgesetzten am Montag den Beleg des Kunden.";

    @Test
    void rawTextBecomesAWellFormedTreeAndTheCoreRelation() {
        ParsedDocument document = ANALYSIS.analyze(new DocumentRef("test", "pipeline", "1"), TEXT);

        Sentence sentence = document.sentences().get(0);
        assertTrue(sentence.parsed());
        assertEquals(1, sentence.tokens().stream().filter(token -> token.head() == 0).count(), "genau eine Wurzel");
        for (Token token : sentence.tokens()) {
            assertTrue(token.head() >= 0 && token.head() <= sentence.tokens().size());
        }
        assertEquals(List.of("sentences", "tokens", "upos", "lemma", "dependencies"),
                document.components().stream().map(c -> c.layer()).toList());
        assertTrue(ConllU.write(document).contains("6-7\tam\t"));

        ObjectCentricAnalysis analysis = new ObjectCentricExtraction(ExtractionSettings.defaults()).analyze(document);
        CoreRelation core = SalienceProjection.project(analysis).core().get(0);
        assertEquals("Der Sachbearbeiter", core.subjectText());
        assertEquals("übermitteln", core.predicateLemma());
        assertEquals("den Beleg", core.objectText());
    }

    @Test
    void parallelCallsShareOneModelAndGiveTheSameResult() throws Exception {
        String expected = ConllU.write(ANALYSIS.analyze(new DocumentRef("test", "pipeline", "1"), TEXT));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(pool.submit(() -> ConllU.write(
                        ANALYSIS.analyze(new DocumentRef("test", "pipeline", "1"), TEXT))));
            }
            for (Future<String> result : results) {
                assertEquals(expected, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
