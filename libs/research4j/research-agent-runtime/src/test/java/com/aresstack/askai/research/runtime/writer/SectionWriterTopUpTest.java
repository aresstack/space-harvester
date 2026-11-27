package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Live run 5: a part below its word target gets a top-up from what is written, within the set bounds. */
public class SectionWriterTopUpTest {

    private static final String FIRST = "Die Rate stieg auf 15,5 % [1]. Danach kam ein Rückgang [2].";
    private static final String MORE = "Im Jahr 2020 stieg die Rate weiter [3]. Teil eins und Teil zwei zeigen "
            + "den Rückgang [4].";

    private static SectionAssignment assignment(TopUpCriteria topUp) {
        return SectionAssignment.builder("s1").title("Rates").brief("How the rate developed")
                .keyPoints(Arrays.asList("growth")).passages(SectionWriterTest.pack(6)).bookLanguage("German")
                .wordsPerCall(SectionAssignment.MIN_WORDS_PER_CALL).answerTokens(1200).topUp(topUp).build();
    }

    @Test
    public void shortAnswerGetsOneTopUpWithTheWrittenTextAndTheUncitedPassages() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(FIRST).answer(MORE);
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(assignment(new TopUpCriteria(0.85, 1)));
        assertEquals(2, chat.requests.size());
        List<ChatMessage> topUp = chat.requests.get(1);
        assertEquals(FIRST, topUp.get(topUp.size() - 2).getContent());
        String instruction = topUp.get(topUp.size() - 1).getContent();
        assertTrue(instruction, instruction.contains("[3], [4], [5], [6]"));
        assertTrue(instruction, instruction.contains("already written"));
        assertFalse(instruction, instruction.contains("[1],"));
        assertTrue(draft.getText(), draft.getText().startsWith(FIRST));
        assertTrue(draft.getText(), draft.getText().contains("[4]"));
        assertEquals(4, draft.getCitations().size());
        assertTrue(log.toString(), log.toString().contains("top-up 1"));
    }

    @Test
    public void noTopUpWhenOffOrTheAnswerReachesTheShare() {
        SectionWriterTest.ScriptedChat off = new SectionWriterTest.ScriptedChat().answer(FIRST).answer(MORE);
        new SectionWriter(off, null).write(assignment(TopUpCriteria.off()));
        assertEquals(1, off.requests.size());
        SectionWriterTest.ScriptedChat low = new SectionWriterTest.ScriptedChat().answer(FIRST).answer(MORE);
        new SectionWriter(low, null).write(assignment(new TopUpCriteria(0.1, 3)));
        assertEquals(1, low.requests.size());
    }

    @Test
    public void topUpThatAddsNothingIsDroppedAndEndsTheTopUps() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(FIRST).answer(FIRST)
                .answer(MORE);
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(new TopUpCriteria(0.85, 3)));
        assertEquals(2, chat.requests.size());
        assertEquals(FIRST, draft.getText());
    }

    @Test
    public void noTopUpWhenEveryPassageIsCited() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(FIRST).answer(MORE);
        SectionAssignment two = assignment(new TopUpCriteria(0.85, 1)).toBuilder()
                .passages(SectionWriterTest.pack(2)).build();
        new SectionWriter(chat, null).write(two);
        assertEquals(1, chat.requests.size());
    }

    @Test
    public void topUpSentenceWithAnUnsupportedParagraphIsLeftOut() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat().answer(FIRST)
                .answer(MORE + " Nach § 99 gilt die Rate weiter [5].");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(assignment(new TopUpCriteria(0.85, 1)));
        assertTrue(draft.getText(), draft.getText().contains("[4]"));
        assertFalse(draft.getText(), draft.getText().contains("99"));
        assertTrue(log.toString(), log.toString().contains("left out 1"));
    }

    @Test
    public void twoTopUpRoundsByDefault() {
        assertEquals(2, TopUpCriteria.DEFAULT_MAX_CALLS);
    }
}
