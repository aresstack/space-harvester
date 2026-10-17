package com.aresstack.spaceharvester.application.enrichment.language;

import java.util.Locale;

/**
 * Ein Wort oder Satzzeichen mit seiner Stelle im projizierten Text.
 *
 * @param text      Wortlaut
 * @param start     erste Zeichenposition
 * @param end       Position nach dem letzten Zeichen
 * @param wordClass Wortklasse
 */
record Token(String text, int start, int end, WordClass wordClass) {

    String lower() {
        return text.toLowerCase(Locale.ROOT);
    }

    boolean is(WordClass expected) {
        return wordClass == expected;
    }

    boolean punctuation(String mark) {
        return wordClass == WordClass.PUNCTUATION && text.equals(mark);
    }
}
