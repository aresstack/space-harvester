package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Live run 4, books 2 and 3: manufacturer sentences carried the manufacturer's "we" into the book. */
public class VoiceCheckTest {

    private final VoiceCheck voice = new VoiceCheck();

    @Test
    public void aSourcesWeIsFound() {
        assertEquals("unsere", voice.firstPersonWord("Effizienz ist entscheidend, und unsere tragbaren Geräte "
                + "spielen eine entscheidende Rolle bei der Optimierung der Abläufe [1]."));
        assertEquals("wir", voice.firstPersonWord(
                "Für werksweite Strategien implementieren wir einen Unified Namespace."));
        assertEquals("Uns", voice.firstPersonWord("Uns ist Sicherheit wichtig [2]."));
        assertEquals("We", voice.firstPersonWord("We monitor every helmet in real time [1]."));
        assertEquals("our", voice.firstPersonWord("The data stays in our cloud [3]."));
    }

    @Test
    public void wordsThatOnlyContainThePronounAreNoVoice() {
        assertEquals("", voice.firstPersonWord("Die Wirkung der Warnsysteme ist unsicher [1]."));
        assertEquals("", voice.firstPersonWord("Die Wirtschaft investiert in Umweltprojekte [1]."));
        assertEquals("", voice.firstPersonWord("The US agency OSHA reports fewer falls [2]."));
        assertEquals("", voice.firstPersonWord("Wearables were tested by users [1]."));
    }

    @Test
    public void aQuotedWeKeepsItsAuthorsVoice() {
        assertEquals("", voice.firstPersonWord("Der Hersteller schreibt: „Wir schützen jede Messung.“ [2]"));
        assertEquals("", voice.firstPersonWord("The vendor states \"we never sell data\" [1]."));
        assertEquals("", voice.firstPersonWord("Die BAuA nennt das »unser gemeinsames Ziel« [3]."));
    }

    @Test
    public void aQuotationOverSeveralSentencesKeepsItsAuthorsVoice() {
        assertEquals(Arrays.asList("", "", "Wir"), voice.firstPersonWords(Arrays.asList(
                "Der Hersteller schreibt: „Wir schützen Daten [1].", "Unsere Geräte sind sicher [1].“",
                "Wir prüfen das [2].")));
        List<ClaimFlag> flags = voice.check(new CitationValidator().validate("Der Hersteller schreibt: "
                + "„Wir schützen Daten [1]. Unsere Geräte sind sicher [1].“ Wir prüfen das [2].", 3));
        assertEquals(1, flags.size());
        assertEquals("Wir prüfen das [2].", flags.get(0).getSentence());
    }

    @Test
    public void everySentenceInTheFirstPersonIsFlaggedWhereItStands() {
        CitationValidator.Result result = new CitationValidator().validate(
                "Sensoren warnen früh [1].\n\nWir empfehlen Schulungen [2]. Die DGUV nennt Pausen [2].", 3);
        List<ClaimFlag> flags = voice.check(result);
        assertEquals(1, flags.size());
        assertEquals(ClaimFlag.Kind.FIRST_PERSON_VOICE, flags.get(0).getKind());
        assertEquals(1, flags.get(0).getParagraph());
        assertEquals("Wir", flags.get(0).getDetail());
        assertEquals("first-person voice of a source: Wir empfehlen Schulungen [2]. (Wir)",
                flags.get(0).revisionLine());
    }
}
