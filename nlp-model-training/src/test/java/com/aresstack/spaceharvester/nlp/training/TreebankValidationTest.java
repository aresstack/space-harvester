package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TreebankValidationTest {

    @Test
    void countsSpecialLinesAndLabels() {
        TreebankValidation.Report report = TreebankValidation.validate(ConlluSentenceTest.sample());
        assertTrue(report.valid(), report.errors().toString());
        assertEquals(2, report.sentences());
        assertEquals(7, report.words());
        assertEquals(1, report.multiwordTokens());
        assertEquals(1, report.emptyNodes());
        assertEquals(2, report.labels().get("root"));
        assertEquals(0, report.nonProjectiveSentences());
    }

    @Test
    void reportsBrokenTrees() {
        assertFalse(valid(row(1, "2", "nsubj"), row(2, "1", "obj")), "Zyklus ohne Wurzel");
        assertFalse(valid(row(1, "0", "root"), row(2, "0", "root")), "zwei Wurzeln");
        assertFalse(valid(row(1, "0", "root"), row(2, "7", "obj")), "HEAD außerhalb");
        assertFalse(valid(row(1, "0", "root"), row(2, "1", "_")), "DEPREL fehlt");
        assertTrue(valid(row(1, "0", "root"), row(2, "1", "obj")));
    }

    @Test
    void detectsNonProjectiveArcs() {
        assertTrue(TreebankValidation.projective(new int[] {0, 2, 0, 2}));
        // 1 hängt an 3 und überspannt 2, die Wurzel, von der 3 abhängt
        assertFalse(TreebankValidation.projective(new int[] {0, 3, 0, 2}));
    }

    private static boolean valid(String[]... rows) {
        List<String[]> lines = new ArrayList<>(List.of(rows));
        return TreebankValidation.validate(List.of(new ConlluSentence(List.of(), lines))).valid();
    }

    private static String[] row(int id, String head, String deprel) {
        return new String[] {Integer.toString(id), "w" + id, "_", "NOUN", "_", "_", head, deprel, "_", "_"};
    }
}
