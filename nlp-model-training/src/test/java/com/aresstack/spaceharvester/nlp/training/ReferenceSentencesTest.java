package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReferenceSentencesTest {

    static List<ConlluSentence> reference() {
        return ConlluSentence.read(Path.of("model/german-reference-sentences.conllu"));
    }

    @Test
    void referenceSentencesAreValidGsdStyleTrees() {
        TreebankValidation.Report report = TreebankValidation.validate(reference());
        assertEquals(List.of(), report.errors());
        assertEquals(8, report.sentences());
    }

    @Test
    void readsExpectationsWithSubtypes() {
        List<ReferenceSentences.Expectation> expectations = ReferenceSentences.expectations(reference().get(0));
        assertEquals(new ReferenceSentences.Expectation(2, "Sachbearbeiter", "nsubj", 3), expectations.get(0));
        assertEquals(new ReferenceSentences.Expectation(5, "Vorgesetzten", "obl:arg", 3), expectations.get(1));
        assertEquals(new ReferenceSentences.Expectation(10, "Beleg", "obj", 3), expectations.get(3));
    }

    @Test
    void goldTreesPassAllExpectations() {
        List<ConlluSentence> gold = reference().stream().map(InputContract::forTraining).toList();
        ReferenceSentences.Report report = ReferenceSentences.check(reference(),
                input -> gold.stream().filter(s -> s.sentenceId().equals(input.sentenceId())).findFirst().orElseThrow());
        assertEquals(report.checks(), report.passed());
    }
}
