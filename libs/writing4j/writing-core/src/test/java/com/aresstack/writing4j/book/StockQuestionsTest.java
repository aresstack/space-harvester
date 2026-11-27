package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The questions of the mission and their check against mission and fence. Made-up texts (city traffic). */
public class StockQuestionsTest {

    private static final Fence FENCE = new Fence("Ein Sachbuch für Stadtplaner über Verkehrssicherheit "
            + "in Innenstädten.", "Nur der Verkehr in der Stadt.", Collections.singletonList("Tempo 30"),
            Collections.singletonList("Autobahnen"), "Deutschland", "");

    @Test
    public void theMissionQuestionsComeFromTheFenceAlone() {
        Answers chat = new Answers("{\"titel\":\"Sichere Innenstädte\",\"fragen\":[\"Wie wirkt Tempo 30?\"]}");
        StockQuestions.Drafted demanded = new StockQuestions(chat, FENCE, "de", null, null).draftMission();

        assertEquals(Collections.singletonList("Wie wirkt Tempo 30?"), demanded.getQuestions());
        assertTrue(chat.systems.get(0).startsWith("Du planst ein Sachbuch"));
        assertTrue(chat.prompts.get(0).contains("Rand des Themas:\nNur der Verkehr in der Stadt."));
        assertTrue(chat.prompts.get(0).contains("Zusammen decken die Fragen alles ab, was der Auftrag verlangt"));
        assertTrue("critical and evaluative questions come from the mission (M24)", chat.prompts.get(0).contains(
                "kritische und bewertende Fragen dazu, die der Auftrag aufwirft"));
        assertTrue(chat.prompts.get(0).contains("nicht mit der Meinung des Autors"));
    }

    @Test
    public void theCheckAsksAMissedQuestionOnceMoreAndNeverDropsOne() {
        Answers chat = new Answers(
                "{\"urteile\":[{\"nr\":\"F1\",\"grund\":\"Gehört dazu.\",\"urteil\":\"INNEN\"},"
                        + "{\"nr\":\"2\",\"grund\":\"Autobahnen sind ausgeschlossen.\",\"urteil\":\"AUSSEN\"}]}",
                "kein JSON");
        List<StockQuestions.Verdict> verdicts = new StockQuestions(chat, FENCE, "de", null, null).check(Arrays.asList(
                "Wie wirkt Tempo 30?", "Wie sicher sind Autobahnen?", "Was kostet ein Zebrastreifen?"));

        assertEquals(3, verdicts.size());
        assertEquals(StockQuestions.Relation.INNEN, verdicts.get(0).getRelation());
        assertEquals(StockQuestions.Relation.AUSSEN, verdicts.get(1).getRelation());
        assertEquals("missed twice: kept, not checked", StockQuestions.Relation.UNGEPRUEFT,
                verdicts.get(2).getRelation());
        assertEquals(2, chat.prompts.size());
        assertTrue(chat.prompts.get(1), chat.prompts.get(1).contains("[F1] Was kostet ein Zebrastreifen?")
                && !chat.prompts.get(1).contains("Autobahnen?"));
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(text);
        }
        return out.toString();
    }

    /** Answers in turn; records system and user prompt of every call. */
    static final class Answers implements MainModelChat {
        private final LinkedList<String> answers;
        final List<String> systems = new ArrayList<String>();
        final List<String> prompts = new ArrayList<String>();

        Answers(String... answers) {
            this.answers = new LinkedList<String>(Arrays.asList(answers));
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            systems.add(messages.get(0).getContent());
            prompts.add(messages.get(messages.size() - 1).getContent());
            return answers.isEmpty() ? MainModelChatResult.ok("{\"titel\":\"\",\"fragen\":[]}")
                    : MainModelChatResult.ok(answers.removeFirst());
        }

        @Override
        public String modelName() {
            return "answers";
        }
    }
}
