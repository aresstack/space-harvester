package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;
import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OpenAiResponseParserTest {

    private final OpenAiResponseParser parser = new OpenAiResponseParser();

    @Test
    public void parsesToolCallsInCompletionAndAccumulatesStreamedOnes() {
        ChatResponse response = parser.parseCompletion("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"knowledge_search\",\"arguments\":\"{\\\"query\\\":\\\"X\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        assertEquals(ChatFinishReason.TOOL_CALLS, response.finishReason());
        assertEquals("knowledge_search", response.message().toolCalls().get(0).name());
        assertEquals("{\"query\":\"X\"}", response.message().toolCalls().get(0).argumentsJson());

        OpenAiResponseParser.ToolCallAccumulator calls = new OpenAiResponseParser.ToolCallAccumulator();
        calls.add(parser.parseChunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c2\","
                + "\"function\":{\"name\":\"knowledge_read\",\"arguments\":\"{\\\"id\\\":\"}}]}}]}").toolCalls);
        calls.add(parser.parseChunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"\\\"p1\\\"}\"}}]}}]}").toolCalls);
        assertEquals("c2", calls.calls().get(0).id());
        assertEquals("{\"id\":\"p1\"}", calls.calls().get(0).argumentsJson());
    }

    @Test
    public void parsesCompletion() {
        ChatResponse response = parser.parseCompletion("{\"id\":\"x\",\"model\":\"gpt-intern\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hallo\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}");
        assertEquals("Hallo", response.content());
        assertEquals(ChatFinishReason.STOP, response.finishReason());
        assertEquals("gpt-intern", response.model());
        assertEquals(4, response.usage().totalTokens());
    }

    @Test
    public void allZeroUsageIsNotReported() {
        // Der getestete Server meldet usage.* derzeit immer 0.
        ChatResponse response = parser.parseCompletion("{\"choices\":[{\"message\":{\"content\":\"x\"},"
                + "\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}");
        assertFalse(response.usage().isReported());
        assertEquals(ChatFinishReason.LENGTH, response.finishReason());
    }

    @Test
    public void nullContentBecomesEmptyAnswer() {
        ChatResponse response = parser.parseCompletion("{\"choices\":[{\"message\":{\"content\":null}}]}");
        assertEquals("", response.content());
        assertEquals(ChatFinishReason.UNKNOWN, response.finishReason());
    }

    @Test
    public void chunkWithNullContentIsAllowed() {
        OpenAiResponseParser.Chunk chunk = parser.parseChunk(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":null}]}");
        assertNull(chunk.content);
        assertNull(chunk.finishReason);
    }

    @Test
    public void chunkWithoutChoicesIsAllowed() {
        OpenAiResponseParser.Chunk chunk = parser.parseChunk(
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}");
        assertNull(chunk.content);
        assertEquals(5, chunk.usage.totalTokens());
    }

    @Test
    public void errorObjectBecomesProviderError() {
        try {
            parser.parseChunk("{\"error\":{\"message\":\"model overloaded\",\"type\":\"server_error\"}}");
            fail();
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.PROVIDER_ERROR, e.kind());
            assertTrue(e.getMessage().contains("model overloaded"));
        }
    }

    @Test
    public void invalidJsonIsAProtocolError() {
        try {
            parser.parseCompletion("<html>bad gateway</html>");
            fail();
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.PROTOCOL, e.kind());
        }
        try {
            parser.parseCompletion("{\"choices\":[]}");
            fail();
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.PROTOCOL, e.kind());
        }
    }

    @Test
    public void ssePayloadNormalization() {
        assertEquals("{}", OpenAiCompatibleChatAdapter.payloadOf("data: {}"));
        assertEquals("{}", OpenAiCompatibleChatAdapter.payloadOf("data:{}"));
        assertEquals("[DONE]", OpenAiCompatibleChatAdapter.payloadOf("data: [DONE]"));
        assertEquals("{}", OpenAiCompatibleChatAdapter.payloadOf("{}"));
        assertNull(OpenAiCompatibleChatAdapter.payloadOf(""));
        assertNull(OpenAiCompatibleChatAdapter.payloadOf(": keep-alive"));
        assertNull(OpenAiCompatibleChatAdapter.payloadOf("event: message"));
        assertNull(OpenAiCompatibleChatAdapter.payloadOf("data:"));
    }

    @Test
    public void errorMessagesAreShortenedAndRedacted() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("abcdefghij");
        }
        ChatCompletionException error = OpenAiErrors.forStatus(500,
                "{\"error\":{\"message\":\"token sk-geheim invalid " + longText + "\"}}", "sk-geheim");
        assertFalse(error.getMessage().contains("sk-geheim"));
        assertTrue(error.getMessage().length() < 400);
        assertEquals(500, error.statusCode());
    }
}
