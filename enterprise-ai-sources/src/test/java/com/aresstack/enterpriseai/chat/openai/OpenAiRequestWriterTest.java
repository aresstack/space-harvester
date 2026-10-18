package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenAiRequestWriterTest {

    private final OpenAiRequestWriter writer = new OpenAiRequestWriter("gpt-intern", DeveloperRolePolicy.REJECT);

    @Test
    public void writesRolesInOrderAndDefaultModel() {
        JsonObject body = write(writer, new ChatRequest(Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u"),
                ChatMessage.assistant("a"), ChatMessage.user("u2")), null), false);

        assertEquals("gpt-intern", body.get("model").getAsString());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(4, messages.size());
        assertEquals("system", role(messages, 0));
        assertEquals("user", role(messages, 1));
        assertEquals("assistant", role(messages, 2));
        assertEquals("u2", messages.get(3).getAsJsonObject().get("content").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
    }

    @Test
    public void writesAllSupportedOptions() {
        ChatOptions options = ChatOptions.builder().model("anderes-modell").temperature(0.3).topP(0.9).topK(40)
                .maxTokens(512).presencePenalty(0.5).frequencyPenalty(-0.5).endUserId("pseudonym-42").build();
        JsonObject body = write(writer, new ChatRequest(Collections.singletonList(ChatMessage.user("x")), options),
                true);

        assertEquals("anderes-modell", body.get("model").getAsString());
        assertEquals(0.3, body.get("temperature").getAsDouble(), 0.0);
        assertEquals(0.9, body.get("top_p").getAsDouble(), 0.0);
        assertEquals(40, body.get("top_k").getAsInt());
        assertEquals(512, body.get("max_tokens").getAsInt());
        assertEquals(0.5, body.get("presence_penalty").getAsDouble(), 0.0);
        assertEquals(-0.5, body.get("frequency_penalty").getAsDouble(), 0.0);
        assertEquals("pseudonym-42", body.get("user").getAsString());
        assertTrue("streaming is requested in the body", body.get("stream").getAsBoolean());
    }

    @Test
    public void unsetOptionsAreNotSentAndNIsNeverSent() {
        JsonObject body = write(writer, new ChatRequest(Collections.singletonList(ChatMessage.user("x")), null), false);
        for (String name : Arrays.asList("temperature", "top_p", "top_k", "max_tokens", "presence_penalty",
                "frequency_penalty", "stop", "user", "n")) {
            assertFalse(name + " must not be sent", body.has(name));
        }
    }

    @Test
    public void stopIsAlwaysSentAsArrayEvenForASingleSequence() {
        JsonObject body = write(writer, new ChatRequest(Collections.singletonList(ChatMessage.user("x")),
                ChatOptions.builder().stop(Collections.singletonList("ENDE")).build()), false);
        assertTrue(body.get("stop").isJsonArray());
        assertEquals("ENDE", body.getAsJsonArray("stop").get(0).getAsString());
    }

    @Test(expected = com.aresstack.enterpriseai.chat.api.ChatCompletionException.class)
    public void developerRoleIsRejectedByDefault() {
        write(writer, new ChatRequest(Arrays.asList(ChatMessage.developer("regel"), ChatMessage.user("x")), null),
                false);
    }

    @Test
    public void systemPromptIsNeverTurnedIntoDeveloper() {
        for (DeveloperRolePolicy policy : DeveloperRolePolicy.values()) {
            JsonObject body = write(new OpenAiRequestWriter("m", policy), new ChatRequest(Arrays.asList(
                    ChatMessage.system("sys"), ChatMessage.user("x")), null), false);
            assertEquals("system", role(body.getAsJsonArray("messages"), 0));
        }
    }

    @Test
    public void developerRoleCanExplicitlyBeSentAsSystem() {
        OpenAiRequestWriter asSystem = new OpenAiRequestWriter("m", DeveloperRolePolicy.SEND_AS_SYSTEM);
        JsonObject body = write(asSystem, new ChatRequest(Arrays.asList(ChatMessage.developer("regel"),
                ChatMessage.user("x")), null), false);
        assertEquals("system", role(body.getAsJsonArray("messages"), 0));
    }

    @Test
    public void developerRoleCanBeSentUnchanged() {
        OpenAiRequestWriter passThrough = new OpenAiRequestWriter("m", DeveloperRolePolicy.SEND_AS_DEVELOPER);
        JsonObject body = write(passThrough, new ChatRequest(Arrays.asList(ChatMessage.developer("regel"),
                ChatMessage.user("x")), null), false);
        assertEquals("developer", role(body.getAsJsonArray("messages"), 0));
    }

    @Test
    public void keepsUnicodeContentIntact() {
        JsonObject body = write(writer, new ChatRequest(Collections.singletonList(
                ChatMessage.user("Grüße, Straße, 😀 \"zitiert\"\n")), null), false);
        assertEquals("Grüße, Straße, 😀 \"zitiert\"\n",
                body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    public void writesToolsToolCallsAndToolResultsWithoutToolChoice() {
        JsonObject body = write(writer, new ChatRequest(Arrays.asList(ChatMessage.user("Suche X"),
                ChatMessage.assistantToolCalls("", Collections.singletonList(
                        new com.aresstack.enterpriseai.astu.chat.ChatToolCall("call_1", "knowledge_search",
                                "{\"query\":\"X\"}"))),
                ChatMessage.tool("call_1", "Treffer")), null,
                Collections.singletonList(new com.aresstack.enterpriseai.astu.chat.ChatToolDefinition(
                        "knowledge_search", "Sucht", "{\"type\":\"object\",\"properties\":{\"query\":"
                        + "{\"type\":\"string\"}}}"))), false);

        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        assertEquals("knowledge_search", tool.getAsJsonObject("function").get("name").getAsString());
        assertTrue(tool.getAsJsonObject("function").getAsJsonObject("parameters").has("properties"));
        assertFalse(body.has("tool_choice"));
        JsonArray messages = body.getAsJsonArray("messages");
        JsonObject call = messages.get(1).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("{\"query\":\"X\"}", call.getAsJsonObject("function").get("arguments").getAsString());
        assertEquals("tool", role(messages, 2));
        assertEquals("call_1", messages.get(2).getAsJsonObject().get("tool_call_id").getAsString());
    }

    private static JsonObject write(OpenAiRequestWriter writer, ChatRequest request, boolean stream) {
        return JsonParser.parseString(writer.write(request, stream)).getAsJsonObject();
    }

    private static String role(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("role").getAsString();
    }
}
