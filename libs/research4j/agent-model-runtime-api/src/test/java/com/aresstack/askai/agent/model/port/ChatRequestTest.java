package com.aresstack.askai.agent.model.port;

import org.junit.Test;

import java.time.Duration;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatRequestTest {

    private static final ModelSlot JUDGE = ModelSlot.of("judge");

    @Test
    public void defaultsAreTextNoToolsModelDefaults() {
        ChatRequest request = ChatRequest.builder(JUDGE).message(ModelMessage.user("hi")).build();

        assertEquals(JUDGE, request.getSlot());
        assertEquals(OutputFormat.Kind.TEXT, request.getOutputFormat().getKind());
        assertEquals(ThinkingMode.DEFAULT, request.getThinking());
        assertTrue(request.getTools().isEmpty());
        assertNull(request.getTemperature());
        assertEquals(0, request.getMaxOutputTokens());
        assertEquals(0, request.getContextTokens());
        assertNull(request.getTimeout());
    }

    @Test
    public void carriesToolsSchemaAndToolRoundTrip() {
        ToolCall call = new ToolCall("", "search_web", "{\"query\":\"x\"}");
        ChatRequest request = ChatRequest.builder(ModelSlot.of("synthesis"))
                .message(ModelMessage.system("role"))
                .message(ModelMessage.user("go"))
                .message(ModelMessage.assistant("", "thought", Collections.singletonList(call)))
                .message(ModelMessage.toolResult("search_web", "", "{\"hits\":[]}"))
                .tool(new ToolDefinition("search_web", "Search", null))
                .outputFormat(OutputFormat.jsonSchema("{\"type\":\"object\"}"))
                .thinking(ThinkingMode.LOW)
                .temperature(0.2)
                .maxOutputTokens(512)
                .contextTokens(8192)
                .timeout(Duration.ofSeconds(90))
                .build();

        assertEquals(4, request.getMessages().size());
        assertEquals(ModelMessage.Role.TOOL, request.getMessages().get(3).getRole());
        assertEquals("search_web", request.getMessages().get(3).getToolName());
        assertEquals("search_web", request.getMessages().get(2).getToolCalls().get(0).getName());
        assertEquals("{\"type\":\"object\",\"properties\":{}}", request.getTools().get(0).getParametersSchemaJson());
        assertEquals("{\"type\":\"object\"}", request.getOutputFormat().getSchemaJson());
        assertEquals(8192, request.getContextTokens());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsEmptyMessages() {
        ChatRequest.builder(JUDGE).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateToolNames() {
        ChatRequest.builder(JUDGE)
                .tool(new ToolDefinition("a", "", null))
                .tool(new ToolDefinition("a", "", null));
    }

    @Test
    public void rejectsInvalidNumbers() {
        expectIllegal(() -> ChatRequest.builder(JUDGE).temperature(-0.1));
        expectIllegal(() -> ChatRequest.builder(JUDGE).temperature(Double.NaN));
        expectIllegal(() -> ChatRequest.builder(JUDGE).maxOutputTokens(-1));
        expectIllegal(() -> ChatRequest.builder(JUDGE).contextTokens(-1));
        expectIllegal(() -> ChatRequest.builder(JUDGE).timeout(Duration.ZERO));
        expectIllegal(() -> OutputFormat.jsonSchema(" "));
        expectIllegal(() -> ModelMessage.toolResult("", "", "x"));
    }

    @Test
    public void listsAreImmutable() {
        ChatRequest request = ChatRequest.builder(JUDGE).message(ModelMessage.user("hi")).build();
        try {
            request.getMessages().add(ModelMessage.user("more"));
            fail("messages must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void resultBecomesTheNextAssistantTurn() {
        ToolCall call = new ToolCall("c1", "read_page", null);
        ChatResult result = new ChatResult(null, "why", Collections.singletonList(call),
                ChatResult.FinishReason.TOOL_CALLS, null, "local", null);

        ModelMessage next = result.toAssistantMessage();

        assertEquals(ModelMessage.Role.ASSISTANT, next.getRole());
        assertEquals("why", next.getThinking());
        assertEquals("{}", next.getToolCalls().get(0).getArgumentsJson());
        assertEquals(-1, result.getUsage().getPromptTokens());
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
