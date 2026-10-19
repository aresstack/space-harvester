package com.aresstack.spaceharvester.application.tree;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Position einer Seite unter ihren Geschwistern aus der nativen Confluence-Payload: {@code position} oder
 * {@code extensions.position}, wie Confluence sie bei manuell sortierten Seiten liefert. Fehlt sie (oder steht dort
 * {@code "none"}), ordnet Confluence nach Titel; dann ist sie {@code null}. Die Position gehört zu keinem Hash.
 */
public final class SiblingPosition {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SiblingPosition() {
    }

    /** @return die Position oder {@code null}, wenn die Payload keine (lesbare) enthält */
    public static Long of(NativePayload payload) {
        if (payload == null || payload.mediaType() == null || !payload.mediaType().contains("json")) {
            return null;
        }
        try {
            JsonNode page = JSON.readTree(payload.bytes());
            Long position = number(page.path("position"));
            return position != null ? position : number(page.path("extensions").path("position"));
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Long number(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isString() && node.asString().matches("-?\\d{1,18}")) {
            return Long.parseLong(node.asString());
        }
        return null;
    }
}
