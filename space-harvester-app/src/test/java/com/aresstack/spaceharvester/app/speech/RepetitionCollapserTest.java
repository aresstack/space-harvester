package com.aresstack.spaceharvester.app.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RepetitionCollapserTest {

    @Test
    void collapsesWordAndEllipsisLoopsFromGreedyDecoding() {
        String recognized = "Ich dachte mir, du kannst mal im Internet nach weiter unterteis so"
                + " ...".repeat(200)
                + " ZARB-K-Suchen und mir alle detailierte Zusammenfassungen erstellen."
                + " den".repeat(300);

        assertEquals("Ich dachte mir, du kannst mal im Internet nach weiter unterteis so ..."
                        + " ZARB-K-Suchen und mir alle detailierte Zusammenfassungen erstellen. den",
                RepetitionCollapser.collapse(recognized));
    }

    @Test
    void collapsesRepeatedPhrasesIgnoringCaseAndPunctuation() {
        assertEquals("Vielen Dank fürs Zuschauen.",
                RepetitionCollapser.collapse(
                        "Vielen Dank fürs Zuschauen. vielen dank fürs Zuschauen! Vielen Dank fürs Zuschauen."));
    }

    @Test
    void keepsNaturalDoubleWordsAndOrdinaryText() {
        String text = "Das ist sehr sehr gut, und das das Ergebnis passt.";

        assertEquals(text, RepetitionCollapser.collapse(text));
    }

    @Test
    void passesBlankInputThrough() {
        assertNull(RepetitionCollapser.collapse(null));
        assertEquals("  ", RepetitionCollapser.collapse("  "));
    }
}
