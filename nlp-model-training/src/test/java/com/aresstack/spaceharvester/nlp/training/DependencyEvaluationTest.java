package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class DependencyEvaluationTest {

    @Test
    void computesAttachmentScoresAndRelations() {
        ConlluSentence gold = sentence(new String[][] {
                {"1", "Er", "PRON", "2", "nsubj"},
                {"2", "liest", "VERB", "0", "root"},
                {"3", "Bücher", "NOUN", "2", "obj"},
                {"4", ".", "PUNCT", "2", "punct"}});
        ConlluSentence predicted = sentence(new String[][] {
                {"1", "Er", "PRON", "2", "nsubj"},
                {"2", "liest", "VERB", "0", "root"},
                {"3", "Bücher", "NOUN", "2", "obl"},
                {"4", ".", "PUNCT", "3", "punct"}});
        DependencyEvaluation.Metrics metrics = DependencyEvaluation.evaluate(List.of(gold), List.of(predicted));
        assertEquals(4, metrics.words());
        assertEquals(75.0, metrics.uas());
        assertEquals(50.0, metrics.las());
        assertEquals(3, metrics.wordsWithoutPunct());
        assertEquals(100.0, metrics.uasWithoutPunct());
        assertEquals(66.67, metrics.lasWithoutPunct());
        DependencyEvaluation.Relation obj = metrics.relations().get("obj");
        assertEquals(1, obj.gold());
        assertEquals(0, obj.predicted());
        assertEquals(0.0, obj.recall());
        DependencyEvaluation.Relation obl = metrics.relations().get("obl");
        assertEquals(0, obl.gold());
        assertEquals(1, obl.predicted());
        assertEquals(1, metrics.topLabelConfusions().get("obj → obl"));
        assertEquals(100.0, metrics.relations().get("nsubj").f1());
    }

    static ConlluSentence sentence(String[][] rows) {
        return new ConlluSentence(List.of(), java.util.Arrays.stream(rows)
                .map(r -> new String[] {r[0], r[1], "_", r[2], "_", "_", r[3], r[4], "_", "_"}).toList());
    }
}
