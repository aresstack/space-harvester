package com.aresstack.spaceharvester.source.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Für Testdienste: Alle Modellaufrufe streamen ({@link RemoteAi#streamed}); eine gewöhnliche Chat-Antwort wird hier
 * als Server-Sent Events mit einem Abschnitt und {@code [DONE]} geliefert, wenn die Anfrage {@code "stream":true} trägt.
 */
public final class ChatSse {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChatSse() {
    }

    /** Ob die Anfrage gestreamt werden will. */
    public static boolean wanted(String requestBody) {
        return requestBody != null && requestBody.replace(" ", "").contains("\"stream\":true");
    }

    /** Die Antwort {@code json} ({@code chat.completion}) als Ereignisstrom. */
    public static String of(String json) {
        JsonNode root = JSON.readTree(json);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        ObjectNode chunk = JSON.createObjectNode();
        if (root.hasNonNull("model")) {
            chunk.put("model", root.get("model").asString());
        }
        ArrayNode choices = chunk.putArray("choices");
        ObjectNode delta = choices.addObject().put("index", 0).putObject("delta").put("role", "assistant");
        if (message.hasNonNull("content")) {
            delta.put("content", message.get("content").asString());
        }
        if (message.has("tool_calls")) {
            ArrayNode calls = delta.putArray("tool_calls");
            int index = 0;
            for (JsonNode call : message.get("tool_calls")) {
                ObjectNode copy = ((ObjectNode) call).deepCopy();
                copy.put("index", index++);
                calls.add(copy);
            }
        }
        String finish = choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asString() : "stop";
        ((ObjectNode) choices.get(0)).put("finish_reason", finish);
        if (root.has("usage")) {
            chunk.set("usage", root.get("usage"));
        }
        return "data: " + chunk + "\n\ndata: [DONE]\n\n";
    }
}
