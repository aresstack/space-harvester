package com.aresstack.writing4j.planwriter;

import com.aresstack.writing4j.sectionplan.SectionPlan;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aresstack.writing4j.planwriter.PlanSectionWriterTest.ev;
import static com.aresstack.writing4j.planwriter.PlanSectionWriterTest.plan;
import static com.aresstack.writing4j.planwriter.PlanSectionWriterTest.q;
import static com.aresstack.writing4j.planwriter.PlanSectionWriterTest.section;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The writing memory from plan and manuscript, deterministic and within its budget. Made-up texts (city traffic). */
public class WritingMemoryTest {

    private static final List<SectionPlan.Contradiction> NONE = Collections.emptyList();
    private static final SectionPlan.Contradiction W1 = new SectionPlan.Contradiction("W1", "FRAGE", "q1", "stadt-a",
            "stadt-b", "WIDERSPRUCH");

    private final SectionPlan.Section first = section("s1", "Tempo 30 und Unfälle", 300,
            Arrays.asList(ev("p1", "stadt-a", "q1"), ev("p2", "stadt-b", "q2")), Collections.<SectionPlan.Evidence>emptyList(),
            Arrays.asList(q("q1", "Wie wirkt Tempo 30 auf Unfälle?", "Belegt"),
                    q("q2", "Wie wirkt Tempo 30 auf Lärm?", "Belegt")), Collections.singletonList(W1));
    private final SectionPlan.Section second = section("s2", "Lärm und Anwohner", 300,
            Arrays.asList(ev("p2", "stadt-b", "q2"), ev("p3", "stadt-c", "q3")), Collections.<SectionPlan.Evidence>emptyList(),
            Arrays.asList(q("q2", "Wie wirkt Tempo 30 auf Lärm?", "Belegt"),
                    q("q3", "Was sagen Anwohner?", "Belegt")), Collections.singletonList(W1));
    private final SectionPlan.Section third = section("s3", "Umsetzung", 300,
            Collections.singletonList(ev("p4", "stadt-d", "q4")), Collections.<SectionPlan.Evidence>emptyList(),
            Collections.singletonList(q("q4", "Wie setzt eine Stadt Tempo 30 um?", "Belegt")), NONE);
    private final SectionPlan plan = plan(first, second, third);

    @Test
    public void theFirstSectionOnlySeesWhatComesLater() {
        WritingMemory.MemoryBlock block = new WritingMemory(null, true).forSection(plan, first,
                Collections.<WrittenSection>emptyList(), numbers("p1", "p2"));

        assertTrue(block.getText(), block.getText().contains("Folgt später, hier nicht vorwegnehmen: „Lärm und "
                + "Anwohner“ (Wie wirkt Tempo 30 auf Lärm? | Was sagen Anwohner?)"));
        assertTrue(block.getText().contains("„Umsetzung“"));
        assertFalse(block.getText().contains("Schon"));
        assertEquals("", block.getPreviousEnd());
        assertTrue(block.getGlossary().isEmpty());
        assertTrue(block.getWritten().isEmpty());
    }

    @Test
    public void aLaterSectionKnowsWhatIsAnsweredCitedPresentedAndIntroduced() {
        String text = "Die Verkehrsplanung für Ruhige Innenstädte (VRI) sieht Tempo 30 vor [1]. "
                + "Nach Tempo 30 sank die Zahl schwerer Unfälle [1]. Der Lärm nahm hörbar ab [2].";
        WrittenSection done = written("s1", "Tempo 30 und Unfälle", text,
                new WrittenSection.Citation(1, "p1", "stadt-a", "Text eins"),
                new WrittenSection.Citation(2, "p2", "stadt-b", "Text zwei"));

        WritingMemory.MemoryBlock block = new WritingMemory(null, true).forSection(plan, second,
                Collections.singletonList(done), numbers("p2", "p3"));

        String memory = block.getText();
        assertTrue(memory, memory.contains("Schon beantwortet in „Tempo 30 und Unfälle“ (darauf verweisen, nicht "
                + "wiederholen): Wie wirkt Tempo 30 auf Lärm?"));
        assertFalse(memory.contains("Was sagen Anwohner?\n"));
        assertTrue(memory, memory.contains("Schon zitiert in „Tempo 30 und Unfälle“: [1]"));
        assertTrue(memory, memory.contains("Widerspruch schon dargestellt in „Tempo 30 und Unfälle“"));
        assertTrue(memory, memory.contains("„Umsetzung“"));
        assertTrue(block.getPreviousEnd(), block.getPreviousEnd().endsWith("Der Lärm nahm hörbar ab."));
        assertFalse(block.getPreviousEnd().contains("["));
        assertEquals(Collections.singletonList("VRI: Verkehrsplanung für Ruhige Innenstädte"), block.getGlossary());
        assertEquals(Collections.singletonList(text), block.getWritten());
    }

    @Test
    public void aFailedSectionCountsAsNotWritten() {
        WrittenSection broken = new WrittenSection("s1", "Tempo 30 und Unfälle", "Kapitel", 300,
                WrittenSection.Status.FEHLER, "Schreiber: Zeitüberschreitung", "", 0, null, null, null, null, null,
                "m", "h", 1, 1L, 1L);
        WritingMemory.MemoryBlock block = new WritingMemory(null, true).forSection(plan, second,
                Collections.singletonList(broken), numbers("p2", "p3"));

        assertFalse(block.getText().contains("Schon"));
        assertTrue(block.getWritten().isEmpty());
    }

    @Test
    public void theMemoryStaysWithinItsBudgetAndDropsWholeLines() {
        WrittenSection done = written("s1", "Tempo 30 und Unfälle", "Der Lärm nahm hörbar ab [2]. Ein zweiter Satz "
                + "steht hier [1]. Und ein dritter, langer Satz schließt den Abschnitt ab [1].",
                new WrittenSection.Citation(1, "p1", "stadt-a", "Text eins"),
                new WrittenSection.Citation(2, "p2", "stadt-b", "Text zwei"));
        WritingMemory tight = new WritingMemory(new WritingMemory.Budget(150, 60, 0), true);

        WritingMemory.MemoryBlock block = tight.forSection(plan, second, Collections.singletonList(done),
                numbers("p2", "p3"));

        assertTrue(block.getText().length() <= 150);
        assertTrue(block.getLeft() > 0);
        assertTrue(block.getText().startsWith("- Schon beantwortet"));
        for (String line : block.getText().split("\n")) {
            assertTrue(line, line.startsWith("- "));
        }
        assertTrue(block.getPreviousEnd().length() <= 60);
        assertTrue(block.getPreviousEnd(), block.getPreviousEnd().endsWith("ab."));
    }

    @Test
    public void englishLines() {
        WritingMemory.MemoryBlock block = new WritingMemory(null, false).forSection(plan, third,
                Collections.<WrittenSection>emptyList(), numbers("p4"));

        assertEquals("", block.getText());
        block = new WritingMemory(null, false).forSection(plan, first, Collections.<WrittenSection>emptyList(),
                numbers("p1"));
        assertTrue(block.getText().startsWith("- Comes later, do not anticipate it here: \"Lärm und Anwohner\""));
    }

    private static WrittenSection written(String id, String title, String text, WrittenSection.Citation... cited) {
        return new WrittenSection(id, title, "Kapitel", 300, WrittenSection.Status.OK, "", text,
                text.split("\\s+").length, Arrays.asList(cited), null, null, null, null, "m", "h", 1, 1L, 1L);
    }

    private static Map<String, Integer> numbers(String... passageIds) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < passageIds.length; i++) {
            out.put(passageIds[i], i + 1);
        }
        return out;
    }
}
