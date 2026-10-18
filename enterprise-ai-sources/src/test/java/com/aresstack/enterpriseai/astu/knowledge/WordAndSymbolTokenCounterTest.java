package com.aresstack.enterpriseai.astu.knowledge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Tokenzählung nach dem festen Zeichenklassen-Vertrag; Erwartungswerte gelten auf JDK 8 wie auf JDK 21. */
public class WordAndSymbolTokenCounterTest {

    private final KnowledgeTokenCounter counter = KnowledgeTokenCounter.wordsAndSymbols();

    @Test
    public void idNamesTheSecondVersionOfTheCountingRule() {
        assertEquals("words-and-symbols-v2", counter.id());
    }

    @Test
    public void wordsJoinLettersDigitsMarksAndUnderscores() {
        assertEquals(1, counter.count("max_tokens"));
        assertEquals(1, counter.count("Größe")); // o + U+0308
        assertEquals(1, counter.count("Wort­teil")); // weiches Trennzeichen
        assertEquals(1, counter.count("x‍y")); // Zero Width Joiner
        assertEquals(1, counter.count("abc123"));
        assertEquals(2, counter.count("日本語 한국어"));
        assertEquals(1, counter.count("𠀀𠀁")); // CJK Erweiterung B
        assertEquals(1, counter.count("𐐀𐐨")); // Deseret
    }

    @Test
    public void punctuationDashesQuotesSymbolsAndEmojiCountOneEach() {
        assertEquals(3, counter.count("Ja – nein"));
        assertEquals(3, counter.count("E-Mail"));
        assertEquals(4, counter.count("„Hallo“!"));
        assertEquals(2, counter.count("3 €"));
        assertEquals(2, counter.count("x²"));
        assertEquals(2, counter.count("Hi 👋"));
        assertEquals(2, counter.count("👋🏽")); // Emoji und Hautton-Modifikator sind zwei Symbole
        assertEquals(2, counter.count("→ ✓"));
        assertEquals(3, counter.count("a。b"));
    }

    @Test
    public void whitespaceAndControlsDoNotStartTokensButSeparateThem() {
        assertEquals(2, counter.count("a b"));
        assertEquals(2, counter.count("a　b"));
        assertEquals(2, counter.count("a b"));
        assertEquals(0, counter.count(" \t\n   　"));
        assertEquals(0, counter.count(null));
        assertEquals(0, counter.count(""));
        assertEquals(3, counter.count("a\u0001b")); // Steuerzeichen zählt wie ein Symbol
    }
}
