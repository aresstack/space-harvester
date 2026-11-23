package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Der Doppelklick findet das angeklickte Wort im vorlesbaren Text, auch wenn die Anzeige anders umbricht (aus dem
 * Enterprise AI Client, {@code ReadAloudStartTest}).
 */
class ReadAloudStartTest {

    private static final String SPOKEN = "Betrieb\n\nErster Absatz mit Text.\n\nZweiter Absatz,\nder umbricht.";

    @Test
    void startsAtTheClickedWordOfTheParagraph() {
        String paragraph = "Zweiter Absatz, der umbricht.\n";
        int at = ReadAloudStart.find(SPOKEN, paragraph, paragraph.indexOf("umbricht") + 3);
        assertEquals("umbricht.", SPOKEN.substring(at));
    }

    @Test
    void startsAtTheFirstWordWhenTheClickHitsIt() {
        String paragraph = "Erster Absatz mit Text.";
        int at = ReadAloudStart.find(SPOKEN, paragraph, 2);
        assertEquals(SPOKEN.indexOf("Erster"), at);
    }

    @Test
    void whitespaceImagesAndUnknownTextAreNoHit() {
        assertEquals(-1, ReadAloudStart.find(SPOKEN, "Erster  Absatz", 7), "Leerzeichen zwischen Wörtern");
        assertEquals(-1, ReadAloudStart.find(SPOKEN, "   \n", 1), "Leerraum");
        assertEquals(-1, ReadAloudStart.find(SPOKEN, " \n", 0), "Bildplatzhalter");
        assertEquals(-1, ReadAloudStart.find(SPOKEN, "Bildunterschrift", 3), "nicht vorlesbar");
    }
}
