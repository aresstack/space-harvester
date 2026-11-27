package com.aresstack.writing4j.memory;

import com.aresstack.writing4j.memory.Contradiction.Difference;
import com.aresstack.writing4j.memory.Contradiction.Scope;
import com.aresstack.writing4j.memory.Contradiction.Side;
import com.aresstack.writing4j.memory.Contradiction.State;
import com.aresstack.writing4j.memory.Contradiction.Verdict;
import com.aresstack.writing4j.memory.EvidenceVerdict.Kind;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 17's record in the memory of component 7: taken in with checks, the latest record per pair holds, open
 * means WIDERSPRUCH, not shown, not decided and both sides still direct evidence; on disk as lines of version 2.
 */
public class ResearchMemoryContradictionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final String TEMPO_A = "In Wohngebieten gilt innerorts eine Höchstgeschwindigkeit von 30 km/h, sobald die "
            + "Gemeinde eine Tempo-30-Zone ausweist.";
    static final String TEMPO_B = "Innerorts gilt in Wohngebieten stets 50 km/h; eine Tempo-30-Zone darf die Gemeinde "
            + "dort nicht ausweisen.";
    static final String TEMPO_C = "In Österreich dürfen Gemeinden auf Gemeindestraßen eigenständig Tempo 30 verordnen.";

    /** Two questions on one topic, with direct evidence from three sources. */
    static ResearchMemory memory(ResearchMemory memory) throws Exception {
        memory.addTopic(ResearchTopic.found("T-tempo", "Tempo im Ort", "", null, "", 1L).withFence(ResearchTopic.Fence.INNEN, "", "", 1L));
        memory.addQuestion(ResearchQuestion.open("F01", "Wo gilt Tempo 30?", "T-tempo",
                ResearchQuestion.Origin.NUTZER, 1L));
        memory.addQuestion(ResearchQuestion.open("F02", "Wer ordnet Tempo 30 an?", "T-tempo",
                ResearchQuestion.Origin.NUTZER, 1L));
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/regeln", TEMPO_B));
        memory.record(direct("F02", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C));
        return memory;
    }

    static EvidenceVerdict direct(String question, String passage, String source, String url, String text) {
        return EvidenceVerdict.judged(question, passage, source, url, PassageText.key(text), Kind.BELEGT,
                text.substring(0, text.lastIndexOf(' ', 40)), "nennt die Regel", "gemma4:12b", 1, 10L);
    }

    static Side side(ResearchMemory memory, String question, String passage) {
        return Side.of(memory.verdict(question, passage));
    }

    @Test
    public void theIdComesFromTheTwoTextsInEitherOrder() throws Exception {
        ResearchMemory memory = memory(new ResearchMemory());
        Side a = side(memory, "F01", "p1");
        Side b = side(memory, "F01", "p2");
        assertEquals(Contradiction.idOf(a, b), Contradiction.idOf(b, a));
        assertTrue(Contradiction.idOf(a, b), Contradiction.idOf(a, b).matches("widerspruch-[0-9a-f]{16}"));
        assertFalse(Contradiction.idOf(a, b).equals(Contradiction.idOf(a, side(memory, "F02", "p3"))));
        try {
            Contradiction.judged(Scope.FRAGE, "F01", a, new Side("F02", "p9", "s9", "", a.getTextKey()), 0.9,
                    Verdict.VEREINBAR, Difference.KEINER, "gleich", "m", 1, 1L);
            fail("the same text cannot contradict itself");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("same text"));
        }
    }

    @Test
    public void aContradictionNeedsKnownQuestionsAndTopics() throws Exception {
        ResearchMemory memory = memory(new ResearchMemory());
        Side a = side(memory, "F01", "p1");
        Side b = side(memory, "F01", "p2");
        try {
            memory.record(Contradiction.judged(Scope.FRAGE, "F99", a, b, 0.8, Verdict.WIDERSPRUCH, null, "x", "m", 1,
                    1L));
            fail("unknown question");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("F99"));
        }
        try {
            memory.record(Contradiction.judged(Scope.THEMA, "T-x", a, b, 0.8, Verdict.WIDERSPRUCH, null, "x", "m", 1,
                    1L));
            fail("unknown topic");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("T-x"));
        }
        try {
            memory.record(Contradiction.judged(Scope.FRAGE, "F01", a, new Side("F77", "p7", "s7", "", "other text"),
                    0.8, Verdict.WIDERSPRUCH, null, "x", "m", 1, 1L));
            fail("unknown question of a side");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("F77"));
        }
        assertTrue(memory.contradictions().isEmpty());
    }

    @Test
    public void openMeansContradictionNotShownNotDecidedAndStillEvidence() throws Exception {
        ResearchMemory memory = memory(new ResearchMemory());
        Side a = side(memory, "F01", "p1");
        Side b = side(memory, "F01", "p2");
        Side c = side(memory, "F02", "p3");
        Contradiction tempo = Contradiction.judged(Scope.FRAGE, "F01", a, b, 0.91, Verdict.WIDERSPRUCH,
                Difference.RECHTSRAUM, "A sagt 30 km/h, B sagt 50 km/h für dieselben Straßen", "gemma4:12b", 1, 20L);
        assertEquals("a contradiction has no difference", Difference.KEINER, tempo.getDifference());
        memory.record(tempo);
        memory.record(Contradiction.judged(Scope.THEMA, "T-tempo", a, c, 0.74, Verdict.VEREINBAR,
                Difference.RECHTSRAUM, "C spricht von Österreich", "gemma4:12b", 1, 21L));
        memory.record(Contradiction.failed(Scope.THEMA, "T-tempo", b, c, 0.70, "timeout", "gemma4:12b", 1, 22L));

        assertEquals(3, memory.contradictions().size());
        assertEquals(Arrays.asList(tempo.getId()), ids(memory.openContradictions()));
        assertEquals(Arrays.asList(tempo.getId()), ids(memory.openContradictionsOf("F01")));
        assertEquals("a side on the topic's question counts", Arrays.asList(tempo.getId()),
                ids(memory.openContradictionsOf("T-tempo")));
        assertTrue(memory.openContradictionsOf("F02").isEmpty());
        assertTrue(memory.openContradictionsOf("unknown").isEmpty());
        Contradiction failed = memory.contradiction(Contradiction.idOf(b, c));
        assertEquals(Contradiction.Status.FEHLER, failed.getStatus());
        assertNull("a failed call has no verdict, never VEREINBAR", failed.getVerdict());
        assertEquals("timeout", failed.getProblem());

        memory.record(tempo.withState(State.IM_TEXT_DARGESTELLT, "", 30L));
        assertTrue("shown in the text: no longer open", memory.openContradictions().isEmpty());
        assertEquals("the latest record holds, the pair keeps its place", tempo.getId(),
                memory.contradictions().get(0).getId());
        assertEquals(Verdict.WIDERSPRUCH, memory.contradiction(tempo.getId()).getVerdict());

        memory.record(tempo.withState(State.OFFEN, "", 31L));
        assertEquals(1, memory.openContradictions().size());
        memory.record(EvidenceVerdict.judged("F01", "p2", "s2", "https://verkehr.example.org/regeln",
                PassageText.key(TEMPO_B), Kind.HINWEIS, "", "zweite Lesung", "gemma4:12b", 2, 40L));
        assertTrue("a side that is no longer direct evidence takes the pair out", memory.openContradictions()
                .isEmpty());
        try {
            failed.withState(State.VOM_NUTZER_ENTSCHIEDEN, "A gilt", 50L);
            fail("a pair without a verdict cannot be decided");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no verdict"));
        }
    }

    @Test
    public void aMergedQuestionBringsItsContradictions() throws Exception {
        ResearchMemory memory = memory(new ResearchMemory());
        Contradiction tempo = Contradiction.judged(Scope.FRAGE, "F01", side(memory, "F01", "p1"),
                side(memory, "F01", "p2"), 0.9, Verdict.WIDERSPRUCH, null, "30 gegen 50", "gemma4:12b", 1, 20L);
        memory.record(tempo);
        assertTrue(memory.openContradictionsOf("F02").isEmpty());
        memory.updateQuestion(memory.question("F02").withMerged(Arrays.asList(memory.question("F01")), 30L));
        assertEquals(Arrays.asList(tempo.getId()), ids(memory.openContradictionsOf("F02")));
    }


    @Test
    public void sourcesThatCarryTheSameTextAreOneGroup() throws Exception {
        ResearchMemory memory = memory(new ResearchMemory());
        memory.record(direct("F02", "p4", "s4", "https://kopie.example.net/tempo", TEMPO_A));
        memory.record(EvidenceVerdict.judged("F02", "p5", "s5", "https://nur.example/hinweis", TEMPO_B, Kind.HINWEIS,
                TEMPO_B, "nur ein Hinweis", "gemma4:12b", 1, 10L));

        java.util.Map<String, String> alone = memory.sourceGroups(Arrays.asList("F02"));
        assertEquals(2, alone.size());
        assertFalse(alone.get("s3").equals(alone.get("s4")));

        java.util.Map<String, String> both = memory.sourceGroups(Arrays.asList("F01", "F02"));
        assertEquals(4, both.size());
        assertEquals("s1 and s4 carry text A", both.get("s1"), both.get("s4"));
        assertFalse(both.get("s1").equals(both.get("s2")));
        assertFalse(both.get("s1").equals(both.get("s3")));
        assertNull("a lead is no direct evidence", both.get("s5"));
        assertEquals(3, new java.util.HashSet<String>(both.values()).size());
    }

    private static List<String> ids(List<Contradiction> contradictions) {
        List<String> ids = new java.util.ArrayList<String>();
        for (Contradiction contradiction : contradictions) {
            ids.add(contradiction.getId());
        }
        return ids;
    }
}
