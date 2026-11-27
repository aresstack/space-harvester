package com.aresstack.writing4j.engine;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 10: a section plan binds the pack's passages to the key points (direct evidence first), each passage may
 * carry a note for the writer, and a bound part takes the passages of its own key points.
 */
public class SectionWriterBindingTest {

    private static SectionAssignment.Builder bound(int k, List<String> points, List<List<Integer>> binding) {
        return SectionAssignment.builder("s1").title("Tempo 30").keyPoints(points).passages(SectionWriterTest.pack(k))
                .bookLanguage("German").wordsPerCall(450).answerTokens(1200).keyPointPassages(binding);
    }

    @Test
    public void theBindingIsCheckedAgainstTheKeyPointsAndThePack() {
        SectionAssignment a = bound(6, Arrays.asList("a", "b"), Arrays.asList(Arrays.asList(1, 1, 3),
                Collections.<Integer>emptyList())).build();
        assertTrue(a.isBound());
        assertEquals("a passage twice in one list once", Arrays.asList(Arrays.asList(1, 3),
                Collections.<Integer>emptyList()), a.getKeyPointPassages());
        assertEquals("a copy keeps it", a.getKeyPointPassages(), a.toBuilder().build().getKeyPointPassages());
        assertFalse(bound(6, Arrays.asList("a"), null).build().isBound());
        try {
            bound(6, Arrays.asList("a", "b"), Collections.singletonList(Arrays.asList(1))).build();
            fail("one list per key point");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("one list of passages per key point"));
        }
        try {
            bound(6, Arrays.asList("a"), Collections.singletonList(Arrays.asList(9))).build();
            fail("a number the pack lacks");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("passage 9"));
        }
    }

    @Test
    public void aBoundPartTakesOnePassageOfEachKeyPointInTurnUpToTheCap() {
        SectionAssignment a = bound(6, Arrays.asList("a", "b", "c"), Arrays.asList(Arrays.asList(1, 2, 3),
                Collections.singletonList(4), Arrays.asList(6, 5))).passagesPerCall(4).build();
        assertEquals("1, 4, 6 in the first round, then 2; in pack order", Arrays.asList(1, 2, 4, 6),
                numbers(SectionWriter.boundPassages(a, 0, 3)));
        assertEquals(Arrays.asList(1, 2, 3), numbers(SectionWriter.boundPassages(a, 0, 1)));
        assertEquals(Arrays.asList(4, 5, 6), numbers(SectionWriter.boundPassages(a, 1, 3)));
        SectionAssignment all = a.toBuilder().passagesPerCall(0).build();
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6), numbers(SectionWriter.boundPassages(all, 0, 3)));
        assertTrue(SectionWriter.consecutive(SectionWriter.boundPassages(all, 0, 3)));
        assertFalse(SectionWriter.consecutive(SectionWriter.boundPassages(a, 1, 3)));
    }

    @Test
    public void aBoundSectionInOnePartSeesOnlyItsPassagesAndCitesThePacksNumbers() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat()
                .answer("Die Rate stieg auf 15,5 % im Jahr 2020 weiter [1]. Danach kam ein Rückgang [2].");
        SectionAssignment a = bound(6, Collections.singletonList("Entwicklung der Rate"),
                Collections.singletonList(Arrays.asList(5, 2))).build();

        SectionDraft draft = new SectionWriter(chat, null).write(a);

        assertTrue(draft.getFailure(), draft.isOk());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("[1] \"\"\"Passage 2 sagt:")
                && prompt.contains("[2] \"\"\"Passage 5 sagt:"));
        assertFalse("only the bound passages", prompt.contains("Passage 1 sagt") || prompt.contains("Passage 6 sagt"));
        assertEquals("the markers go back to the pack's numbers", "Die Rate stieg auf 15,5 % im Jahr 2020 weiter [2]. "
                + "Danach kam ein Rückgang [5].", draft.getText());
        assertEquals(Arrays.asList(2, 5), new ArrayList<Integer>(draft.getCitations().keySet()));
        assertEquals("p-5", draft.getCitations().get(5).getPassageId());
    }

    @Test
    public void eachPartOfABoundSectionGetsThePassagesOfItsOwnKeyPoints() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat()
                .answer("Die Rate stieg auf 15,5 % im Jahr 2020 weiter [2].")
                .answer("Danach kam ein Rückgang der Rate [1].");
        SectionAssignment a = bound(4, Arrays.asList("Anstieg", "Rückgang"), Arrays.asList(Arrays.asList(1, 3),
                Arrays.asList(4, 2))).sectionWords(900).build();

        SectionDraft draft = new SectionWriter(chat, null).write(a);

        assertTrue(draft.getFailure(), draft.isOk());
        assertEquals(2, chat.requests.size());
        String first = chat.requests.get(0).get(1).getContent();
        String second = chat.requests.get(1).get(1).getContent();
        assertTrue(first, first.contains("- Anstieg\n") && first.contains("Passage 1 sagt")
                && first.contains("Passage 3 sagt") && !first.contains("Passage 2 sagt"));
        assertTrue(second, second.contains("- Rückgang\n") && second.contains("Passage 2 sagt")
                && second.contains("Passage 4 sagt") && !second.contains("Passage 3 sagt"));
        assertEquals("Die Rate stieg auf 15,5 % im Jahr 2020 weiter [3].\n\nDanach kam ein Rückgang der Rate [2].",
                draft.getText());
    }

    @Test
    public void aNoteStandsNextToItsPassageWithTheSourceLetter() {
        List<EvidencePassage> passages = Arrays.asList(
                new EvidencePassage(1, "src-a", "", "p-1", "Tempo 30 senkt den Lärm.", "direct evidence"),
                new EvidencePassage(2, "src-b", "", "p-2", "Der Verband fordert mehr Zonen.", "only a lead"),
                new EvidencePassage(3, "src-b", "", "p-3", "Ohne Vermerk."));
        SectionAssignment a = SectionAssignment.builder("s1").title("Tempo 30").keyPoints(Arrays.asList("Lärm"))
                .passages(passages).bookLanguage("German").build();
        String prompt = SectionWriterPrompt.userPrompt(a);
        assertTrue(prompt, prompt.contains("[1] \"\"\"Tempo 30 senkt den Lärm.\"\"\" (source A; direct evidence)\n"));
        assertTrue(prompt, prompt.contains("[2] \"\"\"Der Verband fordert mehr Zonen.\"\"\" (source B; only a "
                + "lead)\n"));
        assertTrue(prompt, prompt.contains("[3] \"\"\"Ohne Vermerk.\"\"\" (source B)\n"));

        SectionAssignment one = a.toBuilder().passages(Collections.singletonList(passages.get(0))).build();
        assertTrue("one source, no letter", SectionWriterPrompt.userPrompt(one).contains("[1] \"\"\"Tempo 30 senkt "
                + "den Lärm.\"\"\" (direct evidence)\n"));
        assertEquals("a new number keeps the note", "direct evidence", passages.get(0).withNumber(4).getNote());
        assertEquals(4, passages.get(0).withNumber(4).getNumber());
        assertEquals("blanks of a note are single", "a b", new EvidencePassage(1, "s", "", "p", "t", " a \n b ")
                .getNote());
        List<ChatMessage> first = SectionWriterPrompt.first(a);
        assertEquals(2, first.size());
    }

    private static List<Integer> numbers(List<EvidencePassage> passages) {
        List<Integer> out = new ArrayList<Integer>();
        for (EvidencePassage passage : passages) {
            out.add(passage.getNumber());
        }
        return out;
    }
}
