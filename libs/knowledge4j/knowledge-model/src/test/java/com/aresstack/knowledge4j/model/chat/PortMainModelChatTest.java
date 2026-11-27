package com.aresstack.knowledge4j.model.chat;

import com.aresstack.knowledge4j.model.testing.FakeModelPort;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ModelMessage;
import com.aresstack.knowledge4j.model.OutputFormat;
import com.aresstack.knowledge4j.model.ThinkingMode;
import com.aresstack.knowledge4j.model.TestSlots;
import com.aresstack.knowledge4j.model.role.RoleOutput;
import com.aresstack.knowledge4j.model.role.ModelRole;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PortMainModelChat} over a fake {@link com.aresstack.knowledge4j.model.ModelPort}: what reaches the port. */
public class PortMainModelChatTest {

    // A non-scoping phase drives the GENERIC contract (proposedCommand + scope). The scoping phase now has its
    // own ScopingAssistantOutput contract, tested separately; this test pins the generic command path.
    @Test
    public void jsonCompletionsAskThePortForGenericJsonOrTheGivenSchema() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueRaw("{\"a\":1}");
        port.enqueueRaw("{\"b\":2}");
        PortMainModelChat chat = new PortMainModelChat(port, TestSlots.SCOPE, null);
        java.util.List<ChatMessage> messages = Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u"));

        assertEquals("{\"a\":1}", chat.completeJson(messages, 0.2, 64, null).getText());
        assertEquals("{\"b\":2}", chat.completeJson(messages, 0.2, 64, "{\"type\":\"object\"}").getText());

        ChatRequest generic = port.requests().get(0);
        assertEquals(OutputFormat.Kind.JSON, generic.getOutputFormat().getKind());
        assertEquals(Boolean.FALSE,
                generic.getTruncate(), "research calls never let Ollama cut the prompt (PR 17)");
        assertEquals(TestSlots.SCOPE, generic.getSlot());
        assertEquals(Double.valueOf(0.2), generic.getTemperature());
        assertEquals(64, generic.getMaxOutputTokens());
        assertNull(generic.getTimeout(), "no caller timeout = the platform default");
        ChatRequest schema = port.requests().get(1);
        assertEquals(OutputFormat.Kind.JSON_SCHEMA, schema.getOutputFormat().getKind());
        assertEquals("{\"type\":\"object\"}", schema.getOutputFormat().getSchemaJson());
        assertEquals("slot scope", chat.modelName());
    }

    @Test
    public void aJsonAnswerCutOffAtItsBudgetFailsAndProseIsFlagged() {
        FakeModelPort port = new FakeModelPort();
        port.reportTelemetry(900, 64, com.aresstack.knowledge4j.model.ChatResult.FinishReason.LENGTH,
                "gemma4:e2b", "");
        port.enqueueRaw("{\"a\":");
        port.enqueueRaw("Ein langer Text");
        PortMainModelChat chat = new PortMainModelChat(port, TestSlots.SCOPE, null);
        java.util.List<ChatMessage> messages = Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u"));

        MainModelChatResult json = chat.completeJson(messages, 0.2, 64, null);
        assertEquals(MainModelChatResult.Status.INVALID_RESPONSE,
                json.getStatus(), "a cut-off JSON answer never reaches the parser");
        assertTrue(json.getDetail().contains("cut off"));
        MainModelChatResult prose = chat.complete(messages, 0.2, 64);
        assertTrue(prose.isOk(), "prose stays a result");
        assertTrue(prose.isCutOff(), "but is flagged");
    }

    @Test
    public void contractRolesSendThinkingOffAndChatRolesLeaveItToTheSlot() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueRaw("readable");
        port.enqueueRaw("Hallo");
        java.util.List<ChatMessage> messages = Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u"));
        ModelRole judge = ModelRole.builder("judge", TestSlots.SCOPE).systemPrompt("judge")
                .output(RoleOutput.text("Verdict")).thinking(ThinkingMode.OFF).build();
        ModelRole conversation = ModelRole.builder("conversation", TestSlots.CONVERSATION).systemPrompt("talk")
                .output(RoleOutput.text("Reply")).build();
        PortMainModelChat.forRole(port, judge, null).complete(messages, 0.0, 24);
        PortMainModelChat.forRole(port, conversation, null).complete(messages, 0.4, 4096);
        assertEquals(ThinkingMode.OFF, port.requests().get(0).getThinking());
        assertEquals(24, port.requests().get(0).getMaxOutputTokens(), "the judge keeps its budget of 24");
        assertEquals(ThinkingMode.DEFAULT, port.requests().get(1).getThinking());
    }
}
