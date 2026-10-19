package com.aresstack.enterpriseai.astu.acropolis.navigation;

import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.astu.chat.ChatUsage;
import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.chat.api.ChatStreamListener;
import com.aresstack.enterpriseai.chat.api.ChatTask;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Die Antwort des Sprachmodells wird zeilenweise gelesen; fehlende Nummern behalten die Analyse-Bezeichnung. */
public class ChatSectionLabelerTest {

    @Test
    public void readsNumberedLines() {
        assertEquals(Arrays.asList("Belegimport", "Urlaubsantrag"),
                ChatSectionLabeler.parse("1: Belegimport\n2) Urlaubsantrag\n", 2));
    }

    @Test
    public void missingNumbersStayOpenForTheAnalysisLabel() {
        assertEquals(Arrays.asList("Belegimport", null), ChatSectionLabeler.parse("1: Belegimport\nUnsinn", 2));
        assertNull(ChatSectionLabeler.parse(null, 1).get(0));
        assertEquals("Satzzeichen weg", "Urlaub", ChatSectionLabeler.parse("1: \"Urlaub\".", 1).get(0));
    }

    @Test
    public void moreThanFortySectionsAreNamedInBatchesOfForty() {
        assertBatches(41, Arrays.asList(40, 1));
        assertBatches(85, Arrays.asList(40, 40, 5));
    }

    @Test
    public void aFailedBatchKeepsOnlyItsOwnAnalysisLabels() {
        NumberingChat chat = new NumberingChat();
        chat.failOnCall = 2;
        List<String> labels = new ChatSectionLabeler(chat, () -> "kipitz:test").labels("Handbuch", sections(85));
        assertEquals(85, labels.size());
        assertEquals("Paket 1 benannt", "Abschnitt 40", labels.get(39));
        assertNull("Paket 2 gescheitert", labels.get(40));
        assertNull(labels.get(79));
        assertEquals("Paket 3 benannt", "Abschnitt 5", labels.get(84));
    }

    private static void assertBatches(int count, List<Integer> expected) {
        NumberingChat chat = new NumberingChat();
        List<String> labels = new ChatSectionLabeler(chat, () -> "kipitz:test").labels("Handbuch", sections(count));
        assertEquals(expected, chat.batchSizes);
        assertEquals(count, labels.size());
        for (String label : labels) {
            assertNotNull("jeder Abschnitt benannt", label);
        }
        assertEquals("Nummern je Paket ab 1, Zuordnung bleibt", "Abschnitt 1", labels.get(40));
    }

    private static List<String> sections(int count) {
        List<String> texts = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            texts.add("Text des Abschnitts " + i + ".");
        }
        return texts;
    }

    /** Antwortet je nummerierter Zeile mit „n: Abschnitt n“ und merkt sich die Paketgrößen. */
    private static final class NumberingChat implements ChatCompletionPort {
        final List<Integer> batchSizes = new ArrayList<Integer>();
        int failOnCall = -1;

        @Override
        public ChatResponse complete(ChatRequest request) {
            if (batchSizes.size() + 1 == failOnCall) {
                batchSizes.add(-1);
                throw new IllegalStateException("Modell nicht erreichbar");
            }
            String prompt = request.messages().get(1).content();
            StringBuilder answer = new StringBuilder();
            int lines = 0;
            for (String line : prompt.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).matches("\\d+")) {
                    answer.append(line, 0, colon).append(": Abschnitt ").append(line, 0, colon).append("\n");
                    lines++;
                }
            }
            batchSizes.add(lines);
            return new ChatResponse(ChatMessage.assistant(answer.toString()), ChatFinishReason.STOP,
                    ChatUsage.notReported(), "test");
        }

        @Override
        public ChatTask stream(ChatRequest request, ChatStreamListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
