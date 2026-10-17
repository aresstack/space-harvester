package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class InputContractTest {

    @Test
    void trainingFormKeepsOnlyContractColumnsAndTree() {
        ConlluSentence training = InputContract.forTraining(ConlluSentenceTest.sample().get(0));
        assertEquals(6, training.lines().size(), "Mehrwort-Zeile und leerer Knoten entfallen");
        assertArrayEquals(new String[] {"1", "Er", "_", "PRON", "_", "_", "2", "nsubj", "_", "_"},
                training.lines().get(0));
        assertArrayEquals(new String[] {"5", "Haus", "_", "NOUN", "_", "_", "2", "obl", "_", "_"},
                training.lines().get(4), "MISC fällt weg");
    }

    @Test
    void parsingFormHasNoGoldTree() {
        ConlluSentence input = InputContract.forParsing(ConlluSentenceTest.sample().get(0));
        input.lines().forEach(line -> {
            assertEquals("_", line[ConlluSentence.HEAD]);
            assertEquals("_", line[ConlluSentence.DEPREL]);
        });
    }

    @Test
    void replacesUpos() {
        ConlluSentence sentence = ConlluSentenceTest.sample().get(1);
        assertEquals("X", InputContract.withUpos(sentence, List.of("X")).words().get(0)[ConlluSentence.UPOS]);
        assertThrows(IllegalArgumentException.class, () -> InputContract.withUpos(sentence, List.of()));
    }
}
