package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Lesbare Ansichten der nativen Payload, nur zur Anzeige: eingerücktes JSON und das Confluence-Storage-Format
 * ({@code body.storage.value}) als Quelltext. Bytegenau bleibt nur der Rohexport („Speichern unter …“).
 */
final class Payloads {

    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private Payloads() {
    }

    static boolean isJson(NativePayload payload) {
        String type = payload.mediaType().toLowerCase(Locale.ROOT);
        return type.startsWith("application/json") || type.contains("+json");
    }

    /** Render source text only, always complete ({@link ProgressiveText} fills it); never decode arbitrary binary data into a Swing text component. */
    static String display(NativePayload payload) {
        if (!isText(payload)) {
            return "Binärinhalt (" + payload.mediaType() + ", " + payload.length()
                    + " Bytes). Die Originaldatei kann in der Leseansicht geöffnet oder gespeichert werden.";
        }
        String text = null;
        if (isJson(payload)) {
            try {
                text = JSON.writeValueAsString(JSON.readTree(payload.bytes()));
            } catch (JacksonException e) {
                // Ungültiges JSON wird unverändert gezeigt.
            }
        }
        if (text == null) {
            text = new String(payload.bytes(), StandardCharsets.UTF_8);
        }
        return text;
    }

    /** Keep binary payload bytes out of diagnostic text fields and text decoders. */
    static boolean isText(NativePayload payload) {
        String type = payload.mediaType().toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || isJson(payload) || type.contains("+xml")
                || type.equals("application/xml") || type.equals("application/javascript")
                || type.equals("application/x-yaml") || type.equals("application/yaml")
                || type.equals("application/sql");
    }

    /** {@code body.storage.value} einer Confluence-Payload. */
    static Optional<String> storage(NativePayload payload) {
        if (!isJson(payload)) {
            return Optional.empty();
        }
        try {
            JsonNode value = JSON.readTree(payload.bytes()).path("body").path("storage").path("value");
            return value.isString() ? Optional.of(value.asString()) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /** Ein JSON-Text eingerückt, sonst unverändert. */
    static String prettyJson(String json) {
        if (json == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(JSON.readTree(json));
        } catch (JacksonException e) {
            return json;
        }
    }
}
