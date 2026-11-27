package com.aresstack.askai.agent.model.inference;

import com.aresstack.askai.agent.model.reranker.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The neutral, dependency-free JSON codec for an {@link InferenceConfigurationDocument}. The wire shape is
 * flat: {@code {formatVersion, configurationRevision, model, baseUrl, chatPath, timeoutMillis}}. Decoding is
 * STRICT — malformed JSON, missing required fields, wrong types, a non-http base URL or a non-positive
 * timeout each become a concrete violation and yield an invalid result with a {@code null} document, never a
 * guessed configuration.
 */
public final class InferenceConfigurationCodec {

    private InferenceConfigurationCodec() {
    }

    // ------------------------------------------------------------------ encode

    public static String toJson(InferenceConfigurationDocument document) {
        InferenceEndpointDescriptor d = document.descriptor;
        StringBuilder sb = new StringBuilder("{");
        num(sb, "formatVersion", document.formatVersion).append(',');
        num(sb, "configurationRevision", document.configurationRevision).append(',');
        str(sb, "model", d.model).append(',');
        str(sb, "baseUrl", d.baseUrl).append(',');
        str(sb, "chatPath", d.chatPath).append(',');
        num(sb, "timeoutMillis", d.timeoutMillis);
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------------ decode (strict)

    @SuppressWarnings("unchecked")
    public static InferenceConfigurationValidationResult parse(String json) {
        List<String> violations = new ArrayList<String>();
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (MiniJson.JsonParseException e) {
            violations.add("invalid JSON: " + e.getMessage());
            return InferenceConfigurationValidationResult.invalid(violations);
        }
        if (!(root instanceof Map)) {
            violations.add("configuration root is not a JSON object");
            return InferenceConfigurationValidationResult.invalid(violations);
        }
        Map<String, Object> o = (Map<String, Object>) root;

        int formatVersion = requireInt(o, "formatVersion", violations);
        if (formatVersion > InferenceConfigurationDocument.CURRENT_FORMAT_VERSION) {
            violations.add("formatVersion " + formatVersion + " is newer than supported "
                    + InferenceConfigurationDocument.CURRENT_FORMAT_VERSION);
        } else if (formatVersion < 1) {
            violations.add("formatVersion must be >= 1 (was " + formatVersion + ")");
        }
        long revision = requireLong(o, "configurationRevision", violations);
        if (revision < 1) {
            violations.add("configurationRevision must be >= 1 (was " + revision + ")");
        }
        String model = requireString(o, "model", violations);
        if (model.trim().isEmpty()) {
            violations.add("model must not be blank");
        }
        String baseUrl = requireString(o, "baseUrl", violations);
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            violations.add("baseUrl must be an http(s) URL");
        }
        String chatPath = requireString(o, "chatPath", violations);
        if (!chatPath.startsWith("/")) {
            violations.add("chatPath must start with '/'");
        }
        long timeout = requireLong(o, "timeoutMillis", violations);
        if (timeout <= 0) {
            violations.add("timeoutMillis must be > 0 (was " + timeout + ")");
        }

        if (!violations.isEmpty()) {
            return InferenceConfigurationValidationResult.invalid(violations);
        }
        return InferenceConfigurationValidationResult.valid(InferenceConfigurationDocument.current(revision,
                new InferenceEndpointDescriptor(model, baseUrl, chatPath, timeout)));
    }

    // ------------------------------------------------------------------ helpers

    private static StringBuilder str(StringBuilder sb, String key, String value) {
        return sb.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
    }

    private static StringBuilder num(StringBuilder sb, String key, long value) {
        return sb.append('"').append(escape(key)).append("\":").append(value);
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String requireString(Map<String, Object> o, String key, List<String> violations) {
        Object value = o.get(key);
        if (value == null) {
            violations.add("missing required string \"" + key + "\"");
            return "";
        }
        if (!(value instanceof String)) {
            violations.add("\"" + key + "\" must be a string");
            return "";
        }
        return (String) value;
    }

    private static int requireInt(Map<String, Object> o, String key, List<String> violations) {
        return (int) requireLong(o, key, violations);
    }

    private static long requireLong(Map<String, Object> o, String key, List<String> violations) {
        Object value = o.get(key);
        if (value == null) {
            violations.add("missing required number \"" + key + "\"");
            return 0;
        }
        if (!(value instanceof Double)) {
            violations.add("\"" + key + "\" must be a number");
            return 0;
        }
        return (long) ((Double) value).doubleValue();
    }
}
