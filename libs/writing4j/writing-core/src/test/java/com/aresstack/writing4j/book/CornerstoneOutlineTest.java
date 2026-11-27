package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.basket.OrderingHint;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The outline from the chosen cornerstones in one call (M24). Made-up texts (city traffic). */
public class CornerstoneOutlineTest {

    private static final Fence FENCE = new Fence("Ein Sachbuch für Stadtplaner über Verkehrssicherheit "
            + "in Innenstädten.", "", Collections.<String>emptyList(), Collections.singletonList("Autobahnen"), "",
            "");

    private static List<CornerstoneOutline.Cornerstone> cornerstones() {
        return Arrays.asList(
                new CornerstoneOutline.Cornerstone("Tempo 30", "Geschwindigkeit", "Nach Tempo 30 sank die Zahl "
                        + "schwerer Unfälle."),
                new CornerstoneOutline.Cornerstone("Schulweg", "Kinder", "Der Schulweg führt über zwei Ampeln."),
                new CornerstoneOutline.Cornerstone("Zebrastreifen", "Kinder", "Am Zebrastreifen haben "
                        + "Fußgänger Vorrang."));
    }

    @Test
    public void oneCallGivesChaptersWithQuestionsAndTheirCornerstones() {
        Recorder chat = new Recorder(0, "Hier die Gliederung:\n"
                + "Kapitel 1: Grundlagen der Geschwindigkeit\n"
                + "- Wie wirkt Tempo 30 auf schwere Unfälle? [1]\n"
                + "**Kapitel 2: Kinder im Verkehr**\n"
                + "- Wie wird der Schulweg sicher? [2, 3]\n"
                + "- Welche Rechte haben Fußgänger am Zebrastreifen? [3; 9]\n"
                + "- Wie wirkt Tempo 30 auf schwere Unfälle? [1]\n"
                + "- Was sagen Eltern? []\n");
        CornerstoneOutline.Result result = new CornerstoneOutline(chat, FENCE, "de", null, null)
                .outline(cornerstones(), Collections.singletonList(new OrderingHint("Zebrastreifen", "Schulweg")));

        assertEquals("one call", 1, chat.prompts.size());
        assertEquals(2, result.getChapters().size());
        assertEquals("Kinder im Verkehr", result.getChapters().get(1).getTitle());
        CornerstoneOutline.Question first = result.getChapters().get(0).getQuestions().get(0);
        assertEquals("Wie wirkt Tempo 30 auf schwere Unfälle?", first.getText());
        assertEquals(Collections.singletonList(0), first.getCornerstones());
        List<CornerstoneOutline.Question> kids = result.getChapters().get(1).getQuestions();
        assertEquals("the same question once", 3, kids.size());
        assertEquals(Arrays.asList(1, 2), kids.get(0).getCornerstones());
        assertEquals("a number outside the list is dropped", Collections.singletonList(2),
                kids.get(1).getCornerstones());
        assertEquals("Was sagen Eltern?", kids.get(2).getText());
        assertTrue(kids.get(2).getCornerstones().isEmpty());

        String prompt = chat.prompts.get(0);
        assertTrue(prompt, prompt.contains("Rahmen des Buches: Ein Sachbuch für Stadtplaner"));
        assertTrue(prompt, prompt.contains("Nicht Gegenstand des Buches: Autobahnen"));
        assertTrue(prompt, prompt.contains("Unten stehen 3 nummerierte Eckpfeiler"));
        assertTrue(prompt, prompt.contains("- Zebrastreifen unter Schulweg"));
        assertTrue(prompt, prompt.contains("\n2. Schulweg: Der Schulweg führt über zwei Ampeln.\n"));
        assertFalse("no concept", prompt.contains("Mindmap"));
    }

    @Test
    public void whatDoesNotFitOneContextGoesInPartsByField() {
        Recorder chat = new Recorder(1, "Kapitel 1: Teil\n- Eine Frage? [1]\n");
        CornerstoneOutline outline = new CornerstoneOutline(chat, FENCE, "de",
                new CornerstoneOutline.Options(240, 10, 1, 3.0, 1), null);
        // the context of the model: room for the prompt with one field, not with all
        int one = outline.prompt(cornerstones(), Arrays.asList(1, 2), null).length() / 3 + 20;
        Recorder sized = new Recorder(one, "Kapitel 1: Tempo\n- Eine Frage? [1]\n- Daneben? [2]\n",
                "Kapitel 1: Kinder\n- Andere Frage? [2]\n", "Kapitel 1: Verkehr\n[3, 1, 3, 7]\n");
        List<List<Integer>> parts = new CornerstoneOutline(sized, FENCE, "de",
                new CornerstoneOutline.Options(240, 10, 1, 3.0, 1), null).parts(cornerstones(), null);
        assertEquals(Arrays.asList(Collections.singletonList(0), Arrays.asList(1, 2)), parts);

        CornerstoneOutline.Result result = new CornerstoneOutline(sized, FENCE, "de",
                new CornerstoneOutline.Options(240, 10, 1, 3.0, 1), null).outline(cornerstones(), null);
        assertEquals("one call per part and one to merge them", 3, sized.prompts.size());
        String merge = sized.prompts.get(2);
        assertTrue(merge, merge.contains("Kapitel der Teile:\nTempo\n1. Eine Frage?\n2. Daneben?\nKinder\n"
                + "3. Andere Frage?\n"));
        assertFalse("no places in the merge", merge.contains("Ampeln"));
        assertEquals(2, result.getChapters().size());
        CornerstoneOutline.Chapter merged = result.getChapters().get(0);
        assertEquals("Verkehr", merged.getTitle());
        assertEquals("each question once, in the order of the answer", 2, merged.getQuestions().size());
        assertEquals("Andere Frage?", merged.getQuestions().get(0).getText());
        assertEquals("numbers of a part map back to the whole list", Collections.singletonList(2),
                merged.getQuestions().get(0).getCornerstones());
        assertEquals(Collections.singletonList(0), merged.getQuestions().get(1).getCornerstones());
        CornerstoneOutline.Chapter left = result.getChapters().get(1);
        assertEquals("a question the merge left out stays under its chapter", "Tempo", left.getTitle());
        assertEquals("Daneben?", left.getQuestions().get(0).getText());
        assertTrue(result.getProblems().isEmpty());
    }

    @Test
    public void anAnswerWithoutChapterIsAskedOnceMore() {
        Recorder chat = new Recorder(0, "Ich kann das nicht.", "Kapitel 1: Alles\n- Eine Frage? [1]");
        CornerstoneOutline.Result result = new CornerstoneOutline(chat, FENCE, "de", null, null)
                .outline(cornerstones(), null);
        assertEquals(2, chat.prompts.size());
        assertEquals(1, result.getChapters().size());
        assertTrue(result.getProblems().isEmpty());
    }

    /** Answers in order (the last one again), records the prompts; {@code context} 0 = unknown. */
    static final class Recorder implements MainModelChat {
        final List<String> prompts = new CopyOnWriteArrayList<String>();
        private final List<String> answers;
        private final int context;

        Recorder(int context, String... answers) {
            this.context = context;
            this.answers = Arrays.asList(answers);
        }

        @Override
        public synchronized MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                                         int maxOutputTokens) {
            prompts.add(messages.get(messages.size() - 1).getContent());
            return MainModelChatResult.ok(answers.get(Math.min(prompts.size(), answers.size()) - 1));
        }

        @Override
        public int contextTokens() {
            return context;
        }

        @Override
        public String modelName() {
            return "recorder";
        }
    }
}
