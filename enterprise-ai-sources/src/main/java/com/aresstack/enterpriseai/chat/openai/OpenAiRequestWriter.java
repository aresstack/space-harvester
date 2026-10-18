package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatToolCall;
import com.aresstack.enterpriseai.astu.chat.ChatToolDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Übersetzt eine neutrale {@link ChatRequest} in den JSON-Body von {@code /chat/completions}. */
final class OpenAiRequestWriter {

    private final String defaultModel;
    private final DeveloperRolePolicy developerRolePolicy;

    OpenAiRequestWriter(String defaultModel, DeveloperRolePolicy developerRolePolicy) {
        this.defaultModel = defaultModel;
        this.developerRolePolicy = developerRolePolicy;
    }

    String write(ChatRequest request, boolean stream) {
        ChatOptions options = request.options();
        JsonObject body = new JsonObject();
        body.addProperty("model", options.model() != null ? options.model() : defaultModel);

        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.messages()) {
            JsonObject json = new JsonObject();
            json.addProperty("role", roleOf(message));
            if (message.images().isEmpty()) {
                json.addProperty("content", message.content());
            } else {
                // Bilder als Inhaltsteile: Text und je Bild eine image_url mit Data-URL (Base64)
                JsonArray parts = new JsonArray();
                if (!message.content().isEmpty()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "text");
                    text.addProperty("text", message.content());
                    parts.add(text);
                }
                for (com.aresstack.enterpriseai.astu.chat.ChatImage image : message.images()) {
                    JsonObject url = new JsonObject();
                    url.addProperty("url", "data:" + image.mediaType() + ";base64,"
                            + java.util.Base64.getEncoder().encodeToString(image.bytes()));
                    JsonObject part = new JsonObject();
                    part.addProperty("type", "image_url");
                    part.add("image_url", url);
                    parts.add(part);
                }
                json.add("content", parts);
            }
            if (message.hasToolCalls()) {
                JsonArray calls = new JsonArray();
                for (ChatToolCall call : message.toolCalls()) {
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments", call.argumentsJson());
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", call.id());
                    entry.addProperty("type", "function");
                    entry.add("function", function);
                    calls.add(entry);
                }
                json.add("tool_calls", calls);
            }
            if (message.toolCallId() != null) {
                json.addProperty("tool_call_id", message.toolCallId());
            }
            messages.add(json);
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            // Werkzeuge nur als "tools"; tool_choice wird nie gesendet (der Server entscheidet selbst).
            JsonArray tools = new JsonArray();
            for (ChatToolDefinition tool : request.tools()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name());
                function.addProperty("description", tool.description());
                try {
                    function.add("parameters", JsonParser.parseString(tool.parametersSchemaJson()));
                } catch (JsonParseException invalid) {
                    throw new ChatCompletionException(ChatErrorKind.INVALID_REQUEST,
                            "tool " + tool.name() + " has an invalid parameter schema");
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "function");
                entry.add("function", function);
                tools.add(entry);
            }
            body.add("tools", tools);
        }

        // Streaming nur über den Body; der Server ignoriert ?stream=true als Queryparameter.
        body.addProperty("stream", stream);
        addIfSet(body, "temperature", options.temperature());
        addIfSet(body, "top_p", options.topP());
        addIfSet(body, "top_k", options.topK());
        addIfSet(body, "max_tokens", options.maxTokens());
        addIfSet(body, "presence_penalty", options.presencePenalty());
        addIfSet(body, "frequency_penalty", options.frequencyPenalty());
        if (!options.stop().isEmpty()) {
            // Immer als Array: der Server lehnt stop als einzelnen String ab.
            JsonArray stop = new JsonArray();
            for (String sequence : options.stop()) {
                stop.add(sequence);
            }
            body.add("stop", stop);
        }
        if (options.endUserId() != null) {
            body.addProperty("user", options.endUserId());
        }
        if (options.reasoningEffort() != null) {
            // Nur wenn im Composer gewählt; gegen das Gateway UNVERIFIED.
            body.addProperty("reasoning_effort", options.reasoningEffort());
        }
        // "n" wird bewusst nie gesendet: der Server akzeptiert es, ignoriert es aber.
        return body.toString();
    }

    private String roleOf(ChatMessage message) {
        switch (message.role()) {
            case SYSTEM:
                return "system";
            case DEVELOPER:
                if (developerRolePolicy == DeveloperRolePolicy.SEND_AS_DEVELOPER) {
                    return "developer";
                }
                if (developerRolePolicy == DeveloperRolePolicy.SEND_AS_SYSTEM) {
                    return "system";
                }
                throw new ChatCompletionException(ChatErrorKind.INVALID_REQUEST,
                        "role DEVELOPER is not supported by the configured chat API (it answers HTTP 500)");
            case USER:
                return "user";
            case ASSISTANT:
                return "assistant";
            case TOOL:
                return "tool";
            default:
                throw new IllegalArgumentException("unsupported role " + message.role());
        }
    }

    private static void addIfSet(JsonObject body, String name, Number value) {
        if (value != null) {
            body.addProperty(name, value);
        }
    }
}
