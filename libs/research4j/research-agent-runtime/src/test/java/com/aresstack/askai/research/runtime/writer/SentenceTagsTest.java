package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SentenceTagsTest {

    private static List<EvidencePassage> pack() {
        return Arrays.asList(
                new EvidencePassage(1, "src-a", "sha-a", "p1", "Die Rate stieg 2020 auf 15,5 %. Das lag z. B. an der Bauflaute."),
                new EvidencePassage(2, "src-b", "sha-b", "p2", "Helme mit Sensoren melden Stürze."));
    }

    @Test
    public void everySentenceGetsAnEightDigitHexTagWithoutWhitespaceBetweenTags() {
        SentenceTags tags = SentenceTags.of(pack());
        assertEquals(3, tags.size());
        String first = tags.tagged(1);
        assertTrue(first, first.matches("(<([0-9a-f]{8})>[^<]+</\\2>){2}"));
        assertTrue("an abbreviation does not end a sentence", first.contains("z. B. an der Bauflaute."));
    }

    @Test
    public void tagCitationsBecomeThePassageNumbersOfTheCitationCheck() {
        SentenceTags tags = SentenceTags.of(pack());
        String t1 = tags.tagged(1).substring(1, 9);
        String t2 = tags.tagged(2).substring(1, 9);
        String answer = "Die Rate lag bei 15,5 % [<" + t1 + ">]. Sensoren melden Stürze [<" + t2 + ">, <" + t1
                + ">]. Erfunden [<deadbeef>].";
        String numbered = tags.toNumbered(answer);
        assertEquals("Die Rate lag bei 15,5 % [1]. Sensoren melden Stürze [2, 1]. Erfunden [0].", numbered);
    }

    @Test
    public void strayTagsAreRemovedAndARepeatedPassageIsCitedOnce() {
        SentenceTags tags = SentenceTags.of(pack());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<([0-9a-f]{8})>").matcher(tags.tagged(1));
        assertTrue(m.find());
        String t1 = m.group(1);
        assertTrue(m.find());
        String t1b = m.group(1);
        String numbered = tags.toNumbered("<" + t1 + ">Die Rate stieg [<" + t1 + ">][<" + t1b + ">].</" + t1 + ">");
        assertEquals("Die Rate stieg [1].", numbered);
    }

    @Test
    public void autoFollowsTheWriterModelAndAnIdForcesTheFormat() {
        assertEquals(CitationFormat.SENTENCE_TAGS, CitationFormat.resolve("auto", "sui-1-24b"));
        assertEquals(CitationFormat.SENTENCE_TAGS, CitationFormat.resolve("", "hf.co/x/SUI-1-24B-GGUF:Q4_K_M"));
        assertEquals(CitationFormat.NUMBERED, CitationFormat.resolve("auto", "gpt-oss:20b"));
        assertEquals(CitationFormat.NUMBERED, CitationFormat.resolve("numbered", "sui-1-24b"));
        assertEquals(CitationFormat.SENTENCE_TAGS, CitationFormat.resolve("sentence-tags", "llama3.1:8b"));
    }

    @Test
    public void sentenceTagPromptShowsTaggedTextBlocksAndAsksForTagCitations() {
        SectionAssignment a = SectionAssignment.builder("s1").title("Rates").passages(pack()).bookLanguage("German")
                .citationFormat(CitationFormat.SENTENCE_TAGS).build();
        List<ChatMessage> messages = SectionWriterPrompt.first(a);
        String system = messages.get(0).getContent();
        String user = messages.get(1).getContent();
        assertTrue(system.contains("[<a1b2c3d4>]"));
        assertFalse(system.contains("like [2]"));
        assertTrue(user, user.contains("<text><"));
        assertTrue(user.contains("Number of XML tags: 3."));
        assertFalse(user.contains("[1] \"\"\""));
        assertTrue(user.contains("with [<tag>] citations"));
    }

    @Test
    public void writerReadsTagCitationsAsNumberedMarkers() {
        SectionAssignment a = SectionAssignment.builder("s1").title("Rates").brief("How the rate developed")
                .keyPoints(Arrays.asList("growth")).passages(SectionWriterTest.pack(6)).bookLanguage("German")
                .wordsPerCall(450).answerTokens(1200).citationFormat(CitationFormat.SENTENCE_TAGS).build();
        String tag = SentenceTags.of(a.getPassages()).tagged(2).substring(1, 9);
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat()
                .answer("Die Rate stieg im Jahr 2020 deutlich an [<" + tag + ">].");
        SectionDraft draft = new SectionWriter(chat, null).write(a);
        assertTrue(draft.isOk());
        assertTrue(draft.getText(), draft.getText().contains("[2]"));
        assertFalse(draft.getText().contains(tag));
        assertTrue(draft.getCitations().containsKey(2));
    }
}
