package com.aresstack.writing4j.engine;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    @Test
    public void markerAboveKIsStripped() {
        CitationValidator.Result r = validator.validate("Satz eins [9]. Satz zwei [2, 9].", 6);
        assertEquals(Arrays.asList("[9]", "[9]"), r.getStrippedMarkers());
        assertEquals("Satz eins. Satz zwei [2].", r.text());
        assertEquals(1, r.getFlags().size());
        assertEquals(ClaimFlag.Kind.UNCITED_SENTENCE, r.getFlags().get(0).getKind());
    }

    /** Live run 4, section 1.2 of book 6: "… die Sicherheit der Arbeiter zu erhöhen [keine Passage]." */
    @Test
    public void aNoteInPlaceOfAMarkerIsStrippedAndTheSentenceStaysUncited() {
        CitationValidator.Result r = validator.validate("Wearables messen den Puls [1]. Das erhöht die Sicherheit "
                + "[keine Passage]. Helme warnen bei Stürzen. [Quelle fehlt] Westen leuchten [n].", 3);
        assertEquals(Arrays.asList("[keine Passage]", "[Quelle fehlt]", "[n]"), r.getStrippedMarkers());
        assertEquals("Wearables messen den Puls [1]. Das erhöht die Sicherheit. Helme warnen bei Stürzen. "
                + "Westen leuchten.", r.text());
        boolean flagged = false;
        for (ClaimFlag flag : r.getFlags()) {
            flagged |= flag.getKind() == ClaimFlag.Kind.UNCITED_SENTENCE
                    && flag.getSentence().equals("Das erhöht die Sicherheit.");
        }
        assertTrue(r.getFlags().toString(), flagged);
    }

    @Test
    public void aBracketInsideASentenceOrWithoutALetterIsNoNote() {
        String text = "Die Werte [Puls und Temperatur] gehen an die Leitstelle [1]. „Die Daten […] bleiben im "
                + "Betrieb“ [2].";
        CitationValidator.Result r = validator.validate(text, 2);
        assertTrue(r.getStrippedMarkers().toString(), r.getStrippedMarkers().isEmpty());
        assertEquals(text, r.text());
    }

    @Test
    public void aBracketAfterAnAbbreviationOrBeforeALowercaseWordOrACommaIsProse() {
        String text = "Die Werte, z. B. [Puls und Temperatur], gehen an die Leitstelle [1]. Sie misst etwa alle 5 "
                + "min. [im Mittel] und speichert die Werte [2]. Puls, d. h. [Herzfrequenz] und Rhythmus, zählt [3]. "
                + "Helme warnen [1]. [Puls und Temperatur], so die Leitstelle, genügen [2].";
        CitationValidator.Result r = validator.validate(text, 3);
        assertTrue(r.getStrippedMarkers().toString(), r.getStrippedMarkers().isEmpty());
        assertEquals(text, r.text());
    }

    @Test
    public void markerAfterFullStopBelongsToSentenceBefore() {
        CitationValidator.Result r = validator.validate("Erster Satz. [1] Zweiter Satz. [2]", 6);
        assertTrue(r.getFlags().toString(), r.getFlags().isEmpty());
        assertEquals(2, r.getParagraphs().get(0).getSentences().size());
    }

    @Test
    public void gapsHeadingsAndBulletsAreHandled() {
        CitationValidator.Result r = validator.validate("## Titel\nText [1].\n\n- Punkt [2].\nGAP: Daten fehlen\nGAP:", 3);
        assertEquals(Arrays.asList("Daten fehlen"), r.getGaps());
        assertEquals("a heading without a sentence is a label, not the start of the paragraph",
                "Text [1].\n\nPunkt [2].", r.text());
        assertEquals(Arrays.asList("Titel"), r.getLabelLines());
    }

    /** Live run 4, section 1.1 of books 2 and 3: the key points of the prompt came back as the last "paragraph". */
    @Test
    public void echoedKeyPointsAreNoProse() {
        String prose = "Die Datenschutzkonferenz empfiehlt Datensparsamkeit [1].";
        CitationValidator.Result listed = validator.validate(prose + "\n\n- Beispiel für IoT-Architekturen\n"
                + "- Risiken für das Persönlichkeitsrecht\n- Datensicherheit als primäre Anforderung", 3);
        assertEquals(prose, listed.text());
        assertEquals(Arrays.asList("Beispiel für IoT-Architekturen", "Risiken für das Persönlichkeitsrecht",
                "Datensicherheit als primäre Anforderung"), listed.getLabelLines());
        assertTrue(listed.getFlags().toString(), listed.getFlags().isEmpty());

        CitationValidator.Result runTogether = validator.validate(prose + "\n\nBeispiel für IoT-Architekturen "
                + "Risiken für das Persönlichkeitsrecht Datensicherheit als primäre Anforderung Stand der Technik im "
                + "Baubereich", 3);
        assertEquals(prose, runTogether.text());
        assertEquals(1, runTogether.getLabelLines().size());
        assertTrue("no uncited paragraph is left to flag", runTogether.getFlags().isEmpty());
    }

    @Test
    public void aCitedSentenceWithoutFullStopStaysProse() {
        CitationValidator.Result r = validator.validate("Die DGUV empfiehlt Schulungen [1]", 3);
        assertEquals("Die DGUV empfiehlt Schulungen [1]", r.text());
        assertTrue(r.getLabelLines().isEmpty());
    }

    /** Live run 4, section 2.2 of book 3: one passage, and the writer filled the word target by repeating itself. */
    @Test
    public void repeatedSentencesAreRemoved() {
        CitationValidator.Result r = validator.validate(
                "Sensoren verbessern die Sicherheit [1]. Warnsysteme sind wichtig.\n\n"
                        + "Warnsysteme sind wichtig. Projektleiter optimieren Abläufe [1].\n\n"
                        + "Sensoren verbessern die Sicherheit [1]. Projektleiter  optimieren Abläufe [1].", 3);
        assertEquals("Sensoren verbessern die Sicherheit [1]. Warnsysteme sind wichtig.\n\n"
                + "Projektleiter optimieren Abläufe [1].", r.text());
        assertEquals(3, r.getRepeatedSentences().size());
        assertEquals("flags point into the text as it stays", 1, r.getFlags().size());
        assertEquals(ClaimFlag.Kind.UNCITED_SENTENCE, r.getFlags().get(0).getKind());
        assertEquals(0, r.getFlags().get(0).getParagraph());
    }

    /** A sign, a unit or another symbol makes another claim, not a repeat; case, spacing and the end do not. */
    @Test
    public void sentencesThatDifferInASymbolAreNoRepeat() {
        CitationValidator.Result r = validator.validate("Die Temperatur beträgt -20 °C [1]. "
                + "Die Temperatur beträgt 20 °C [2]. Der Anteil liegt bei 5 % [1]. Der Anteil liegt bei 5 ‰ [2]. "
                + "Die temperatur  beträgt -20 °C [2]!", 3);
        assertEquals("Die Temperatur beträgt -20 °C [1]. Die Temperatur beträgt 20 °C [2]. "
                + "Der Anteil liegt bei 5 % [1]. Der Anteil liegt bei 5 ‰ [2].", r.text());
        assertEquals(Arrays.asList("Die temperatur beträgt -20 °C [2]!"), r.getRepeatedSentences());
    }

    @Test
    public void aSentenceAnEarlierPartWroteIsRemoved() {
        java.util.Set<String> written = CitationValidator.sentenceKeys("Teil eins [1].\n\nSensoren warnen früh [2].");
        CitationValidator.Result r = validator.validate("Sensoren warnen früh [3]. Teil zwei [2].", 3, written);
        assertEquals("Teil zwei [2].", r.text());
        assertEquals(Arrays.asList("Sensoren warnen früh [3]."), r.getRepeatedSentences());
    }

    /** Live run 4, section 2.2 of book 3: the writer closed with a paragraph of nine questions, some twice. */
    @Test
    public void uncitedQuestionsBecomeOpenPoints() {
        CitationValidator.Result r = validator.validate("Sensoren warnen früh [1].\n\n"
                + "Wie genau überwachen IoT-Sensoren die Arbeitsbedingungen? Wie prüft man Warnsysteme? "
                + "Wie genau überwachen IoT-Sensoren die Arbeitsbedingungen?\nGAP: Wie prüft man Warnsysteme?", 3);
        assertEquals("Sensoren warnen früh [1].", r.text());
        assertEquals(Arrays.asList("Wie prüft man Warnsysteme?",
                "Wie genau überwachen IoT-Sensoren die Arbeitsbedingungen?"), r.getGaps());
        assertEquals(2, r.getQuestions().size());
        assertEquals(1, r.getRepeatedSentences().size());
        assertTrue(r.getFlags().toString(), r.getFlags().isEmpty());
    }

    /** Live run 4, section 2.1 of book 5: seven of ten gap lines were sentences of the section's own text. */
    @Test
    public void aGapLineThatRepeatsTheTextOrAnotherGapLineIsNoOpenPoint() {
        CitationValidator.Result r = validator.validate("Ein weiterer wichtiger Aspekt ist die Gesundheit der "
                + "Arbeiter [2]. Sensoren messen den Puls [1].\n"
                + "GAP: Ein weiterer wichtiger Aspekt ist die Gesundheit der Arbeiter.\n"
                + "GAP: Grenzwerte für Hitzestress\n"
                + "GAP: grenzwerte für hitzestress\n"
                + "GAP: Sensoren messen den Puls [1]", 3);
        assertEquals(Arrays.asList("Grenzwerte für Hitzestress"), r.getGaps());
        assertEquals(Arrays.asList("Ein weiterer wichtiger Aspekt ist die Gesundheit der Arbeiter.",
                "grenzwerte für hitzestress", "Sensoren messen den Puls [1]"), r.getDroppedGaps());
        assertEquals("the text stays as written", 2, r.getParagraphs().get(0).getSentences().size());
    }

    @Test
    public void aGapLineThatRepeatsAnEarlierPartIsNoOpenPoint() {
        CitationValidator.Result r = validator.validate("Teil zwei [2].\nGAP: Teil eins sagt das.\nGAP: Normen", 3,
                CitationValidator.sentenceKeys("Teil eins sagt das [1]."));
        assertEquals(Arrays.asList("Normen"), r.getGaps());
        assertEquals(Arrays.asList("Teil eins sagt das."), r.getDroppedGaps());
    }

    @Test
    public void aQuestionWithItsCitedAnswerStaysProse() {
        CitationValidator.Result r = validator.validate("Was kostet ein Sensor? Die Studie nennt 500 Euro [1].", 3);
        assertEquals("Was kostet ein Sensor? Die Studie nennt 500 Euro [1].", r.text());
        assertTrue(r.getQuestions().isEmpty());
        assertTrue(r.getGaps().isEmpty());
    }

    @Test
    public void uncitedParagraphIsFlagged() {
        CitationValidator.Result r = validator.validate("Belegt [1].\n\nOhne Beleg. Auch ohne.", 3);
        assertEquals(1, r.getFlags().stream().filter(f -> f.getKind() == ClaimFlag.Kind.UNCITED_PARAGRAPH).count());
        assertEquals(2, r.getFlags().stream().filter(f -> f.getKind() == ClaimFlag.Kind.UNCITED_SENTENCE).count());
        assertEquals(Arrays.asList(1), new java.util.ArrayList<Integer>(r.citedNumbers()));
    }

    @Test
    public void abbreviationDoesNotSplit() {
        CitationValidator.Result r = validator.validate("Viele Stoffe, z. B. die Säuren, wirken [1].", 3);
        assertEquals(1, r.getParagraphs().get(0).getSentences().size());
    }

    /** c10 split "§ 87 Abs. 1 Nr. 6 BetrVG" and "Art. 9 Abs. 2 lit. b DSGVO" into pieces without a marker. */
    @Test
    public void aLegalReferenceDoesNotSplitButAWordLikeArtStillEndsASentence() {
        java.util.List<CitationValidator.Sentence> sentences = CitationValidator.sentencesOf("Das regelt § 87 Abs. 1 "
                + "Nr. 6 BetrVG [1]. Danach gilt Art. 9 Abs. 2 lit. b DSGVO [2]. Das klappt auf diese Art. Dann "
                + "folgt Art. 5 [3].");
        assertEquals(4, sentences.size());
        assertEquals("Das regelt § 87 Abs. 1 Nr. 6 BetrVG [1].", sentences.get(0).getText());
        assertEquals("Danach gilt Art. 9 Abs. 2 lit. b DSGVO [2].", sentences.get(1).getText());
        assertEquals("not followed by a number: the sentence ends", "Das klappt auf diese Art.",
                sentences.get(2).getText());
        assertEquals("Dann folgt Art. 5 [3].", sentences.get(3).getText());
    }

    /**
     * The review of 22.11.2026: an abbreviation after a bracket or a quote goes on; one that is also a word or a name
     * ("ABS.", "well lit.", "Sog.", "auf diese Art.") ends a sentence unless the number it stands for follows.
     */
    @Test
    public void anAbbreviationGoesOnOnlyWhereItCannotEndTheSentence() {
        assertEquals(1, CitationValidator.sentencesOf("Die Kamera misst genau (vgl. Abb. 3) [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Die sog. Grüne Welle spart Zeit [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Die Leuchten sind gem. DIN EN 1838 geprüft [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Die Frist folgt gem. § 5 der Satzung [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Das steht in Art. 229 EGBGB und in Rn. 12 [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Rule No. 5 applies to every crossing [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Siehe die 3. Aufl. 2019 des Kommentars [1].").size());
        assertEquals("a date", 1, CitationValidator.sentencesOf("Die Regel gilt seit dem 1. Januar 2024 [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Im 19. Jahrhundert gab es keine Ampeln [1].").size());
        assertEquals("a number that ends a sentence", 2, CitationValidator.sentencesOf("Die Zahl der Unfälle stieg "
                + "auf 12. Danach sank sie wieder [1].").size());
        assertEquals("a name in capitals", 2, CitationValidator.sentencesOf("Das Auto hat ABS. Danach kam ESP "
                + "dazu [1].").size());
        assertEquals(2, CitationValidator.sentencesOf("The road is well lit. The camera reads plates [1].").size());
        assertEquals("the noun Sog", 2, CitationValidator.sentencesOf("Der Lkw erzeugt einen starken Sog. Das "
                + "spürt man auf dem Rad [1].").size());
        assertEquals("a year opens the next sentence", 2, CitationValidator.sentencesOf("Das klappt auf diese Art. "
                + "2023 kam die neue Regel [1].").size());
        assertEquals(2, CitationValidator.sentencesOf("Die Werte gelten gem. Danach folgt die Prüfung [1].").size());
        assertEquals("the review of 22.11.2026: a speaker's statement", 1, CitationValidator.sentencesOf("Der Akku "
                + "hält gem. Herstellerangaben acht Stunden [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Der Akku hält gem. Datenblatt acht Stunden [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Der Akku hält gem. den Angaben acht Stunden [1].").size());
        for (String noun : new String[]{"Angaben des Herstellers", "Anbieterauskunft", "Händlerangaben",
                "Verbandsangaben", "Studie", "Studienlage", "Bericht", "Auskunft"}) {
            assertEquals(noun, 1, CitationValidator.sentencesOf("Der Akku hält gem. " + noun + " acht Stunden [1].")
                    .size());
        }
        assertEquals("an English gem", 2, CitationValidator.sentencesOf("The new app is a real gem. Studies show "
                + "that it cuts speeding [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Die Kamera ist lt. Hersteller wetterfest [1].").size());
        assertEquals(1, CitationValidator.sentencesOf("Lt. Hersteller erkennt die Kamera jedes Kennzeichen [1].")
                .size());
        assertTrue(CitationValidator.continues("(vgl.", "Abb. 3"));
        assertTrue(CitationValidator.continues("Art.", "9 DSGVO"));
        assertFalse(CitationValidator.continues("Art.", "2023 kam"));
        assertFalse(CitationValidator.continues("Art.", ""));
        assertFalse(CitationValidator.continues("Abs.", null));
    }

    /** The sentences of a stored section, as the sentence check and the mission report read them. */
    @Test
    public void aWrittenTextIsReadParagraphByParagraphWithTheMarkersOfEachSentence() {
        java.util.List<CitationValidator.Sentence> sentences = CitationValidator.sentencesOf("In Tempo-30-Zonen sank "
                + "die Zahl der Unfälle [1]. Radwege trennen z. B. Rad und Auto [2, 3].\n\nAuf Hauptstraßen gilt "
                + "meist\nTempo 50. [4]\n\n");
        assertEquals(3, sentences.size());
        assertEquals("In Tempo-30-Zonen sank die Zahl der Unfälle [1].", sentences.get(0).getText());
        assertEquals(new java.util.TreeSet<Integer>(Arrays.asList(2, 3)), sentences.get(1).getCited());
        assertEquals("a marker after the full stop belongs to the sentence",
                "Auf Hauptstraßen gilt meist Tempo 50. [4]", sentences.get(2).getText());
        assertEquals(java.util.Collections.singleton(4), sentences.get(2).getCited());
        assertTrue(CitationValidator.sentencesOf(null).isEmpty());
        assertEquals(CitationValidator.sentenceKeys("Ein Satz [1].\n\nNoch einer."),
                CitationValidator.sentenceKeys("Ein Satz.\n\nNoch einer [2]."));
    }
}
