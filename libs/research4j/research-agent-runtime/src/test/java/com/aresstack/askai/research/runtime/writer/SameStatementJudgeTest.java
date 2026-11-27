package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Component 12, second stage: the model call that decides whether two sentences make the same statement. */
public class SameStatementJudgeTest {

    static final String EARLIER = "Die Unternehmer müssen die Beschäftigten vor dem Einsatz der Wearables informieren.";
    static final String LATER = "Vor dem Einsatz von Wearables sind die Beschäftigten durch den Arbeitgeber zu "
            + "unterrichten.";

    /** Answers from a script ({@code null} = a failed call) and records every call. */
    static final class Chat implements MainModelChat {
        final LinkedList<String> answers = new LinkedList<String>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();
        final List<String> schemas = new ArrayList<String>();
        double temperature = -1;
        int tokens;

        Chat then(String answer) {
            answers.add(answer);
            return this;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.add(new ArrayList<ChatMessage>(messages));
            schemas.add(schemaJson);
            this.temperature = temperature;
            this.tokens = maxOutputTokens;
            String answer = answers.isEmpty() ? null : answers.removeFirst();
            return answer == null ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "no answer")
                    : MainModelChatResult.ok(answer);
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }

    static String verdict(String word, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("grund", reason);
        json.addProperty("urteil", word);
        return json.toString();
    }

    @Test
    public void oneCallWithTheReasonFirstSchemaAtTemperatureZero() {
        String answer = verdict("GLEICH", "Beide verlangen, die Beschäftigten vorab zu informieren.");
        Chat chat = new Chat().then(answer);

        SameStatementJudge.Judgement judgement = new SameStatementJudge(chat, "de").judge(EARLIER, LATER);

        assertEquals(SameStatementJudge.Verdict.SAME, judgement.getVerdict());
        assertTrue(judgement.isJudged());
        assertEquals("Beide verlangen, die Beschäftigten vorab zu informieren.", judgement.getReason());
        assertEquals("the raw answer is kept", Arrays.asList(answer), judgement.getAnswers());
        assertEquals(1, judgement.getAttempts());
        assertEquals("", judgement.getProblem());
        assertEquals(1, chat.calls.size());
        assertEquals(0.0, chat.temperature, 0);
        assertEquals(300, chat.tokens);

        List<ChatMessage> messages = chat.calls.get(0);
        assertEquals(SameStatementJudge.SYSTEM_DE, messages.get(0).getContent());
        String prompt = messages.get(1).getContent();
        assertTrue(prompt.startsWith("Früherer Satz:\n" + EARLIER + "\n\nSpäterer Satz:\n" + LATER + "\n\n"));
        assertTrue(prompt.contains("bringt keine wesentliche neue Information"));
        assertTrue(prompt.contains("nach einer anderen Rechtsgrundlage"));
        assertTrue("examples from another field than the book", prompt.contains("Beispiele aus einem anderen Gebiet"));
        assertFalse(prompt.contains("freiwillig"));
        assertTrue(prompt.endsWith("Antworte als JSON mit den Feldern \"grund\" und \"urteil\"."));

        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        assertEquals("[\"grund\",\"urteil\"]", schema.get("required").toString());
        assertEquals("the reason is generated first", "[grund, urteil]",
                schema.getAsJsonObject("properties").keySet().toString());
        assertEquals("[\"GLEICH\",\"TEILWEISE\",\"VERSCHIEDEN\"]",
                schema.getAsJsonObject("properties").getAsJsonObject("urteil").get("enum").toString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());

        assertEquals("phase.draft.repeat", SameStatementJudge.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.OFF, SameStatementJudge.ROLE.getThinking());
    }

    @Test
    public void anInvalidAnswerGetsOneRepairAttemptWithTheReason() {
        Chat chat = new Chat().then("Die Sätze sind gleich.").then(verdict("VERSCHIEDEN", "Andere Handelnde."));

        SameStatementJudge.Judgement judgement = new SameStatementJudge(chat, "de").judge(EARLIER, LATER);

        assertEquals(SameStatementJudge.Verdict.DIFFERENT, judgement.getVerdict());
        assertEquals(2, judgement.getAttempts());
        assertEquals("Die Sätze sind gleich.", judgement.getAnswers().get(0));
        List<ChatMessage> repair = chat.calls.get(1);
        assertEquals(4, repair.size());
        assertEquals(ChatMessage.Role.ASSISTANT, repair.get(2).getRole());
        assertEquals("Die Sätze sind gleich.", repair.get(2).getContent());
        assertTrue(repair.get(3).getContent().startsWith("Deine Antwort war ungültig: the answer is no JSON"));
    }

    @Test
    public void twoFailedCallsAreAnErrorNeverADifferentStatement() {
        Chat chat = new Chat().then(null).then(null);

        SameStatementJudge.Judgement judgement = new SameStatementJudge(chat, "de").judge(EARLIER, LATER);

        assertEquals(SameStatementJudge.Verdict.ERROR, judgement.getVerdict());
        assertFalse(judgement.isJudged());
        assertEquals(Arrays.asList("", ""), judgement.getAnswers());
        assertTrue(judgement.getProblem(), judgement.getProblem().startsWith("model call failed (TIMEOUT)"));
        assertEquals("the same request twice", chat.calls.get(0), chat.calls.get(1));
    }

    @Test
    public void aVerdictOutsideTheThreeWordsIsInvalid() {
        Chat chat = new Chat().then(verdict("JA", "x")).then("{\"grund\": \"y\"}");

        SameStatementJudge.Judgement judgement = new SameStatementJudge(chat, "de").judge(EARLIER, LATER);

        assertEquals(SameStatementJudge.Verdict.ERROR, judgement.getVerdict());
        assertEquals(2, judgement.getAnswers().size());
        assertTrue(judgement.getProblem().contains("\"urteil\" must be GLEICH, TEILWEISE or VERSCHIEDEN"));
    }

    @Test
    public void caseAndSpacesOfTheAnswerDoNotMatter() {
        SameStatementJudge.Parsed parsed = SameStatementJudge.parse("{\"urteil\": \" teilweise \", "
                + "\"grund\": \"  Der zweite   Satz nennt\\n zusätzlich Kosten. \"}");
        assertEquals(SameStatementJudge.Verdict.PARTLY, parsed.verdict);
        assertEquals("Der zweite Satz nennt zusätzlich Kosten.", parsed.reason);
        assertEquals(SameStatementJudge.Verdict.NOT_JUDGED,
                SameStatementJudge.Judgement.notJudged("x").getVerdict());
    }

    /** Review of PR #265: without the schema a transport may drop the reason; a verdict needs it. */
    @Test
    public void aVerdictWithoutAReasonIsNoAnswer() {
        for (String answer : new String[]{"{\"urteil\": \"GLEICH\"}", "{\"urteil\": \"GLEICH\", \"grund\": 3}",
                "{\"urteil\": \"GLEICH\", \"grund\": \" \"}"}) {
            SameStatementJudge.Parsed parsed = SameStatementJudge.parse(answer);
            assertEquals(answer, null, parsed.verdict);
            assertEquals("\"grund\" must be a text", parsed.problem);
        }
    }

    @Test
    public void anEnglishBookGetsTheEnglishPrompt() {
        Chat chat = new Chat().then(verdict("TEILWEISE", "Adds costs."));
        new SameStatementJudge(chat, "en").judge("Workers must be told.", "Workers must be told about costs.");
        assertEquals(SameStatementJudge.SYSTEM_EN, chat.calls.get(0).get(0).getContent());
        assertTrue(chat.calls.get(0).get(1).getContent().startsWith("Earlier sentence:\nWorkers must be told."));
    }
}
