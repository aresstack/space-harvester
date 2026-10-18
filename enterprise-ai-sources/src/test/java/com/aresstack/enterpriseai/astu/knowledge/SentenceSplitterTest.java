package com.aresstack.enterpriseai.astu.knowledge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class SentenceSplitterTest {

    private final SentenceSplitter splitter = new SentenceSplitter();

    @Test
    public void splitsAtTerminalPunctuationFollowedByASentenceStart() {
        assertEquals(Arrays.asList("Erster Satz.", "Zweiter Satz!", "Dritter?", "Vierter …", "Ende"),
                splitter.split("Erster Satz. Zweiter Satz!  Dritter? Vierter … Ende"));
    }

    @Test
    public void umlautsAndDigitsStartSentences() {
        assertEquals(Arrays.asList("Ja.", "Über alles.", "Ärger.", "42 ist die Antwort."),
                splitter.split("Ja. Über alles. Ärger. 42 ist die Antwort."));
    }

    @Test
    public void doesNotSplitAfterAbbreviationsInitialsAndOrdinals() {
        assertEquals(Collections.singletonList("Das gilt z. B. für Lucene bzw. Solr und Dr. Meier am 1. Januar."),
                splitter.split("Das gilt z. B. für Lucene bzw. Solr und Dr. Meier am 1. Januar."));
        assertEquals(Collections.singletonList("Siehe J. R. R. Tolkien, d.h. Mittelerde, vgl. Kap. Drei."),
                splitter.split("Siehe J. R. R. Tolkien, d.h. Mittelerde, vgl. Kap. Drei."));
        assertEquals(Arrays.asList("Das ist „Dr.“ Meier.", "Ende."),
                splitter.split("Das ist „Dr.“ Meier. Ende."));
        assertEquals(Arrays.asList("Siehe (Abb.) Anhang."), splitter.split("Siehe (Abb.) Anhang."));
        assertEquals(Arrays.asList("Er sagte „Ende.“", "Danach ging er."),
                splitter.split("Er sagte „Ende.“ Danach ging er."));
    }

    @Test
    public void unicodeSpacesSeparateSentences() {
        assertEquals(Arrays.asList("Erster Satz.", "Zweiter Satz."),
                splitter.split("Erster Satz.\u00A0Zweiter Satz."));
        assertEquals(Collections.singletonList("Siehe Dr.\u00A0Meier."), splitter.split("Siehe Dr.\u00A0Meier."));
    }

    @Test
    public void doesNotSplitBeforeLowerCaseOrInsideTokens() {
        assertEquals(Collections.singletonList("Version 1.2.3 ist da. und weiter mit www.example.org."),
                splitter.split("Version 1.2.3 ist da. und weiter mit www.example.org."));
    }

    @Test
    public void closingQuotesAndBracketsStayWithTheirSentence() {
        assertEquals(Arrays.asList("Er sagte: „Fertig.“", "(Wirklich?)", "Ja."),
                splitter.split("Er sagte: „Fertig.“ (Wirklich?) Ja."));
    }

    @Test
    public void sentenceStartsFollowTheFixedCaseTableNotTheJdk() {
        assertEquals(Arrays.asList("Ǆemal kam.", "ǅemal ging.", "Ωμέγα endet.", "Яблоко fällt.", "Ạ ok."),
                splitter.split("Ǆemal kam. ǅemal ging. Ωμέγα endet. Яблоко fällt. Ạ ok."));
        assertEquals(Arrays.asList("Deseret 𐐀 ist alt.", "𐐀 beginnt."),
                splitter.split("Deseret 𐐀 ist alt. 𐐀 beginnt."));
        // Schriften ohne Groß-/Kleinschreibung beginnen keinen Satz; Emoji und Symbole auch nicht.
        assertEquals(Collections.singletonList("Ende. 漢字 folgt. אבג folgt. 👋 folgt. → folgt."),
                splitter.split("Ende. 漢字 folgt. אבג folgt. 👋 folgt. → folgt."));
        assertEquals(Collections.singletonList("Siehe ٣. Punkt."), splitter.split("Siehe ٣. Punkt."));
    }

    @Test
    public void blankInputYieldsNoSentences() {
        assertEquals(Collections.<String>emptyList(), splitter.split("   "));
        assertEquals(Collections.<String>emptyList(), splitter.split(null));
    }
}
