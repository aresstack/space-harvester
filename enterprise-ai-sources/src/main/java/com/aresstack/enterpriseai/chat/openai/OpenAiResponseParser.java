package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;
import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.astu.chat.ChatToolCall;
import com.aresstack.enterpriseai.astu.chat.ChatUsage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Liest Antworten und Stream-Chunks von {@code /chat/completions}. Zustandslos. */
final class OpenAiResponseParser {

    /** Ein gelesener Stream-Chunk; alle Felder dürfen fehlen. */
    static final class Chunk {
        final String content;
        final String finishReason;
        final String model;
        final ChatUsage usage;
        /** Teilstücke von {@code delta.tool_calls}; leer ohne Werkzeugaufrufe. */
        final List<ToolCallDelta> toolCalls;

        Chunk(String content, String finishReason, String model, ChatUsage usage, List<ToolCallDelta> toolCalls) {
            this.content = content;
            this.finishReason = finishReason;
            this.model = model;
            this.usage = usage;
            this.toolCalls = toolCalls;
        }
    }

    /** Ein Stück eines gestreamten Werkzeugaufrufs; die Teile eines Aufrufs teilen den {@code index}. */
    static final class ToolCallDelta {
        final int index;
        final String id;
        final String name;
        final String arguments;

        ToolCallDelta(int index, String id, String name, String arguments) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    /** Setzt die Teilstücke gestreamter Werkzeugaufrufe je {@code index} zusammen. */
    static final class ToolCallAccumulator {
        private final Map<Integer, String[]> calls = new TreeMap<Integer, String[]>();

        void add(List<ToolCallDelta> deltas) {
            for (ToolCallDelta delta : deltas) {
                String[] call = calls.get(Integer.valueOf(delta.index));
                if (call == null) {
                    call = new String[] {"", "", ""};
                    calls.put(Integer.valueOf(delta.index), call);
                }
                if (delta.id != null && !delta.id.isEmpty()) {
                    call[0] = delta.id;
                }
                if (delta.name != null && !delta.name.isEmpty()) {
                    call[1] = call[1] + delta.name;
                }
                if (delta.arguments != null) {
                    call[2] = call[2] + delta.arguments;
                }
            }
        }

        List<ChatToolCall> calls() {
            List<ChatToolCall> result = new ArrayList<ChatToolCall>();
            for (String[] call : calls.values()) {
                if (!call[1].isEmpty()) {
                    result.add(new ChatToolCall(call[0], call[1], call[2]));
                }
            }
            return result;
        }
    }

    /** Liest eine vollständige (Non-Streaming-)Antwort. */
    ChatResponse parseCompletion(String json) {
        JsonObject root = parseObject(json);
        failOnErrorObject(root);
        JsonObject choice = firstChoice(root);
        if (choice == null) {
            throw protocol("response contains no choices");
        }
        String content = "";
        List<ChatToolCall> calls = new ArrayList<ChatToolCall>();
        JsonObject message = object(choice, "message");
        if (message != null) {
            String value = string(message, "content");
            content = value == null ? "" : value;
            ToolCallAccumulator accumulator = new ToolCallAccumulator();
            accumulator.add(toolCallDeltas(message));
            calls = accumulator.calls();
        }
        ChatMessage assistant = calls.isEmpty() ? ChatMessage.assistant(content)
                : ChatMessage.assistantToolCalls(content, calls);
        return new ChatResponse(assistant, finishReason(string(choice, "finish_reason")),
                usage(root), string(root, "model"));
    }

    /** Liest den JSON-Teil einer SSE-{@code data:}-Zeile. */
    Chunk parseChunk(String json) {
        JsonObject root = parseObject(json);
        failOnErrorObject(root);
        JsonObject choice = firstChoice(root);
        String content = null;
        String finish = null;
        List<ToolCallDelta> toolCalls = Collections.<ToolCallDelta>emptyList();
        if (choice != null) {
            JsonObject delta = object(choice, "delta");
            if (delta == null) {
                // Manche Server senden auch im Stream "message" statt "delta".
                delta = object(choice, "message");
            }
            if (delta != null) {
                content = string(delta, "content");
                toolCalls = toolCallDeltas(delta);
            }
            finish = string(choice, "finish_reason");
        }
        return new Chunk(content, finish, string(root, "model"), root.has("usage") ? usage(root) : null,
                toolCalls);
    }

    /** {@code tool_calls} einer Nachricht oder eines Deltas; ohne {@code index} zählt die Position. */
    private static List<ToolCallDelta> toolCallDeltas(JsonObject message) {
        JsonElement element = message.get("tool_calls");
        if (element == null || !element.isJsonArray()) {
            return Collections.<ToolCallDelta>emptyList();
        }
        List<ToolCallDelta> result = new ArrayList<ToolCallDelta>();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                continue;
            }
            JsonObject call = array.get(i).getAsJsonObject();
            int index = integer(call, "index");
            JsonObject function = object(call, "function");
            String arguments = null;
            if (function != null) {
                JsonElement args = function.get("arguments");
                if (args != null && !args.isJsonNull()) {
                    arguments = args.isJsonPrimitive() ? args.getAsString() : args.toString();
                }
            }
            result.add(new ToolCallDelta(index >= 0 ? index : i, string(call, "id"),
                    function == null ? null : string(function, "name"), arguments));
        }
        return result;
    }

    static ChatFinishReason finishReason(String value) {
        if (value == null) {
            return ChatFinishReason.UNKNOWN;
        }
        if ("stop".equals(value)) {
            return ChatFinishReason.STOP;
        }
        if ("length".equals(value)) {
            return ChatFinishReason.LENGTH;
        }
        if ("content_filter".equals(value)) {
            return ChatFinishReason.CONTENT_FILTER;
        }
        if ("tool_calls".equals(value) || "function_call".equals(value)) {
            return ChatFinishReason.TOOL_CALLS;
        }
        return ChatFinishReason.UNKNOWN;
    }

    /** Liest {@code usage}; ein fehlender oder komplett leerer Block (alles 0) gilt als nicht gemeldet. */
    static ChatUsage usage(JsonObject root) {
        JsonObject usage = object(root, "usage");
        if (usage == null) {
            return ChatUsage.notReported();
        }
        int prompt = integer(usage, "prompt_tokens");
        int completion = integer(usage, "completion_tokens");
        int total = integer(usage, "total_tokens");
        if (prompt <= 0 && completion <= 0 && total <= 0) {
            return ChatUsage.notReported();
        }
        return ChatUsage.of(Math.max(prompt, 0), Math.max(completion, 0), Math.max(total, 0));
    }

    /** Liest die Fehlermeldung aus einem Fehler-Body ({@code {"error":{"message":...}}}) oder {@code null}. */
    static String errorMessage(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                return null;
            }
            JsonElement error = element.getAsJsonObject().get("error");
            if (error == null || error.isJsonNull()) {
                return null;
            }
            if (error.isJsonPrimitive()) {
                return error.getAsString();
            }
            if (error.isJsonObject()) {
                return string(error.getAsJsonObject(), "message");
            }
            return null;
        } catch (JsonParseException e) {
            return null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static void failOnErrorObject(JsonObject root) {
        JsonElement error = root.get("error");
        if (error != null && !error.isJsonNull()) {
            String message = errorMessage(root.toString());
            throw new ChatCompletionException(ChatErrorKind.PROVIDER_ERROR,
                    "provider reported an error: " + OpenAiErrors.shorten(message == null ? "unknown" : message));
        }
    }

    private static JsonObject parseObject(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw protocol("expected a JSON object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ChatCompletionException(ChatErrorKind.PROTOCOL, "response is not valid JSON", e);
        }
    }

    private static JsonObject firstChoice(JsonObject root) {
        JsonElement choices = root.get("choices");
        if (choices == null || !choices.isJsonArray()) {
            return null;
        }
        JsonArray array = choices.getAsJsonArray();
        if (array.size() == 0 || !array.get(0).isJsonObject()) {
            return null;
        }
        return array.get(0).getAsJsonObject();
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static int integer(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return -1;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ChatCompletionException protocol(String message) {
        return new ChatCompletionException(ChatErrorKind.PROTOCOL, message);
    }
}
