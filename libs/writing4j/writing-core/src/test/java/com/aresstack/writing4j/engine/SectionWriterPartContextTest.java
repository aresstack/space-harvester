package com.aresstack.writing4j.engine;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stage 1 of the writer proposal: the parts of a section know their section (part context), and a top-up can ask
 * for more on the key points instead of text from every uncited passage. Both are separate switches, off by default.
 */
public class SectionWriterPartContextTest {

    private static final String PART = "Die Rate stieg auf 15,5 % [1]. Danach kam ein Rückgang [2]. "
            + "Teil eins zeigt den Rückgang [1].";
    private static final String PART_TWO = "Im Jahr 2020 stieg die Rate weiter [1]. Teil zwei sagt das ebenso [2].";
    private static final String PART_THREE = "Passage drei sagt, die Rate stieg weiter im Jahr 2020 [3].";

    private static SectionAssignment section(PartContextCriteria context) {
        return SectionAssignment.builder("s1").title("Rates").brief("How the rate developed")
                .keyPoints(Arrays.asList("growth", "decline", "outlook")).passages(SectionWriterTest.pack(9))
                .bookLanguage("German").sectionWords(900).wordsPerCall(300).answerTokens(1200).passagesPerCall(3)
                .previousDigest("Der vorige Abschnitt endete hier.").partContext(context).build();
    }

    private static String system(List<ChatMessage> request) {
        return request.get(0).getContent();
    }

    private static String user(List<ChatMessage> request) {
        return request.get(1).getContent();
    }

    @Test
    public void offByDefaultEveryPartReadsTheOldPrompt() {
        assertFalse(SectionAssignment.builder("x").build().getPartContextCriteria().isEnabled());
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(PART).answer(PART_TWO)
                .answer(PART_THREE);
        new SectionWriter(chat, null).write(section(null));
        assertEquals(3, chat.requests.size());
        for (List<ChatMessage> request : chat.requests) {
            assertTrue(system(request).startsWith(SectionWriterPrompt.WHOLE_SECTION));
            assertTrue(user(request).contains("Key points:"));
            assertTrue(user(request).contains("Write the section now"));
            assertFalse(user(request).contains("SECTION TEXT SO FAR"));
        }
        // the old chaining: a later part gets the last sentences of the part before as its digest
        assertTrue(user(chat.requests.get(1)).contains("PREVIOUS SECTION"));
    }

    @Test
    public void laterPartsKnowTheirPlaceThePlanAndTheTextSoFar() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(PART).answer(PART_TWO)
                .answer(PART_THREE);
        new SectionWriter(chat, null).write(section(new PartContextCriteria(true, 0)));
        assertEquals(3, chat.requests.size());
        String first = user(chat.requests.get(0));
        assertTrue(system(chat.requests.get(0)).startsWith(SectionWriterPrompt.ONE_PART));
        assertTrue(first, first.contains("this is part 1"));
        assertTrue(first, first.contains("- growth - this part"));
        assertTrue(first, first.contains("- decline - a later part"));
        assertTrue(first, first.contains("PREVIOUS SECTION"));
        assertTrue(first, first.contains("Do not conclude or summarise"));
        assertFalse(first, first.contains("SECTION TEXT SO FAR"));

        String second = user(chat.requests.get(1));
        assertTrue(second, second.contains("Write part 2 of 3 now"));
        assertTrue(second, second.contains("- growth - already written"));
        assertTrue(second, second.contains("- decline - this part"));
        assertTrue(second, second.contains("SECTION TEXT SO FAR"));
        assertTrue(second, second.contains("Die Rate stieg auf 15,5 %."));
        assertFalse("the text so far carries no markers", second.contains("15,5 % [1]"));
        assertFalse("the text so far replaces the digest", second.contains("PREVIOUS SECTION"));
        assertTrue(second, second.contains("Continue directly after SECTION TEXT SO FAR"));

        String last = user(chat.requests.get(2));
        assertTrue(last, last.contains("End with the last point and do not summarise the section"));
    }

    @Test
    public void aSectionInOneCallGetsNoPartContext() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(PART);
        SectionAssignment one = section(new PartContextCriteria(true, 0)).toBuilder().sectionWords(300).build();
        new SectionWriter(chat, null).write(one);
        assertEquals(1, chat.requests.size());
        assertTrue(user(chat.requests.get(0)).contains("Write the section now"));
    }

    @Test
    public void theTextSoFarIsCutToItsEnd() {
        String text = "Erster Absatz mit einem Satz. Noch ein Satz.\n\nZweiter Absatz beginnt hier. Er endet [2].";
        assertEquals(SectionWriterPrompt.withoutMarkers(text), SectionWriter.soFar(text, 0));
        String cut = SectionWriter.soFar(text, 40);
        assertTrue(cut, cut.startsWith("[...]"));
        assertTrue(cut, cut.endsWith("Er endet."));
        assertTrue(cut.length() <= 50);
    }

    @Test
    public void topUpByKeyPointsNamesThePointsNotEveryUncitedPassage() {
        String first = "Die Rate stieg auf 15,5 % [1]. Danach kam ein Rückgang [2].";
        String more = "Im Jahr 2020 stieg die Rate weiter [1].";
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(first).answer(more);
        SectionAssignment a = SectionAssignment.builder("s1").title("Rates").keyPoints(Arrays.asList("growth"))
                .passages(SectionWriterTest.pack(6)).bookLanguage("German")
                .wordsPerCall(SectionAssignment.MIN_WORDS_PER_CALL).answerTokens(1200)
                .topUp(new TopUpCriteria(0.85, 1, TopUpCriteria.Focus.KEY_POINTS)).build();
        new SectionWriter(chat, null).write(a);
        assertEquals(2, chat.requests.size());
        List<ChatMessage> topUp = chat.requests.get(1);
        String instruction = topUp.get(topUp.size() - 1).getContent();
        assertTrue(instruction, instruction.contains("(growth)"));
        assertTrue(instruction, instruction.contains("do not add a paragraph per passage"));
        assertFalse(instruction, instruction.contains("[3], [4]"));
    }

    @Test
    public void topUpByKeyPointsEndsLikeTheOldOneWhenEveryPassageIsCited() {
        String first = "Die Rate stieg auf 15,5 % [1]. Danach kam ein Rückgang [2].";
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(first)
                .answer("Teil eins und Teil zwei zeigen den Rückgang [1].");
        SectionAssignment a = SectionAssignment.builder("s1").title("Rates").keyPoints(Arrays.asList("growth"))
                .passages(SectionWriterTest.pack(2)).bookLanguage("German")
                .wordsPerCall(SectionAssignment.MIN_WORDS_PER_CALL).answerTokens(1200)
                .topUp(new TopUpCriteria(0.85, 2, TopUpCriteria.Focus.KEY_POINTS)).build();
        new SectionWriter(chat, null).write(a);
        assertEquals("no new material, no top-up: it could only rephrase", 1, chat.requests.size());
        assertEquals(TopUpCriteria.Focus.UNCITED_PASSAGES, new TopUpCriteria(0.85, 2).getFocus());
    }

    @Test
    public void topUpByKeyPointsOffersTheGapOnlyWayOut() {
        SectionAssignment a = SectionAssignment.builder("s1").title("Rates").keyPoints(Arrays.asList("growth"))
                .passages(SectionWriterTest.pack(4)).bookLanguage("German").build();
        String instruction = last(SectionWriterPrompt.topUpByKeyPoints(a, "Die Rate stieg [1].", 120));
        assertTrue(instruction, instruction.contains("write only the GAP: lines"));
        assertFalse("no invitation to explain from own knowledge", instruction.contains("explain"));
    }

    @Test
    public void theRepairOfALaterPartRewritesThatPartNotTheSection() {
        SectionAssignment part = section(new PartContextCriteria(true, 0)).inSection(
                new SectionAssignment.PartContext(1, 3, Arrays.asList("growth - already written",
                        "decline - this part", "outlook - a later part"), "Die Rate stieg."));
        String repair = last(SectionWriterPrompt.repair(part, new java.util.ArrayList<ClaimFlag>()));
        assertTrue(repair, repair.contains("Write this part again (part 2 of 3)"));
        assertFalse(repair, repair.contains("Write the section again"));
        String whole = last(SectionWriterPrompt.repair(section(null), new java.util.ArrayList<ClaimFlag>()));
        assertTrue(whole, whole.contains("Write the section again"));
    }

    @Test
    public void aKeyPointNamedTwiceKeepsTheStateOfEachOfItsParts() {
        SectionAssignment a = section(null).toBuilder().keyPoints(Arrays.asList("growth", "decline", "growth"))
                .build();
        List<SectionAssignment> parts = SectionWriter.parts(a);
        assertEquals(3, parts.size());
        assertEquals(Arrays.asList("growth - already written", "decline - this part", "growth - a later part"),
                SectionWriter.planLines(parts, 1));
    }

    @Test
    public void aRewriteWithPartContextCarriesItsOwnOldTextAndTheTextSoFar() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(PART).answer(PART_TWO)
                .answer(PART_THREE);
        SectionAssignment a = section(new PartContextCriteria(true, 0)).toBuilder().rewriteComment("präziser")
                .currentText("Alter erster Satz über das Wachstum [1].\n\nAlter zweiter Satz über den Rückgang [2]."
                        + "\n\nAlter dritter Satz über den Ausblick [3].").build();
        new SectionWriter(chat, null).write(a);
        assertEquals(3, chat.requests.size());
        String second = user(chat.requests.get(1));
        assertTrue(second, second.contains("CURRENT TEXT OF THIS PART"));
        assertTrue(second, second.contains("Alter zweiter Satz"));
        assertFalse(second, second.contains("Alter erster Satz"));
        assertTrue(second, second.contains("SECTION TEXT SO FAR"));
        assertTrue(second, second.contains("Write part 2 of 3 now"));
    }

    private static String last(List<ChatMessage> request) {
        return request.get(request.size() - 1).getContent();
    }
}
