package com.aresstack.askai.agent.model.reranker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The neutral, dependency-free JSON codec for a {@link RerankerConfigurationDocument}. A small
 * hand-written reader keeps the module framework-free. Decoding is STRICT: malformed JSON, missing
 * required fields, wrong types, unknown enum values and out-of-range numbers each become a concrete
 * violation and yield an invalid result with a {@code null} document — never a guessed configuration.
 */
public final class RerankerEndpointDescriptorCodec {

    private RerankerEndpointDescriptorCodec() {
    }

    // ------------------------------------------------------------------ encode

    public static String toJson(RerankerConfigurationDocument document) {
        RerankerEndpointDescriptor d = document.descriptor;
        RerankerSelectionConfiguration s = d.selectionConfiguration;
        StringBuilder sb = new StringBuilder("{");
        num(sb, "schemaVersion", document.schemaVersion).append(',');
        num(sb, "configurationRevision", document.configurationRevision).append(',');
        sb.append("\"descriptor\":{");
        str(sb, "provider", d.provider.name()).append(',');
        str(sb, "baseUrl", d.baseUrl).append(',');
        str(sb, "modelName", d.modelName).append(',');
        sb.append("\"capabilities\":[");
        for (int i = 0; i < d.capabilities.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(d.capabilities.get(i).name())).append('"');
        }
        sb.append("],");
        str(sb, "scoreSemantics", d.scoreSemantics.name()).append(',');
        num(sb, "requestTimeoutMillis", d.requestTimeoutMillis).append(',');
        sb.append("\"selection\":{");
        num(sb, "maximumSelectedCandidates", s.maximumSelectedCandidates);
        optional(sb, "absoluteMinimumScore", s.absoluteMinimumScore);
        optional(sb, "minimumTopScoreMargin", s.minimumTopScoreMargin);
        optional(sb, "maximumScoreDropFromBest", s.maximumScoreDropFromBest);
        sb.append("}}}");
        return sb.toString();
    }

    // ------------------------------------------------------------------ decode (strict)

    @SuppressWarnings("unchecked")
    public static RerankerConfigurationValidationResult parse(String json) {
        List<String> violations = new ArrayList<String>();
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (MiniJson.JsonParseException e) {
            violations.add("invalid JSON: " + e.getMessage());
            return RerankerConfigurationValidationResult.invalid(violations);
        }
        if (!(root instanceof Map)) {
            violations.add("configuration root is not a JSON object");
            return RerankerConfigurationValidationResult.invalid(violations);
        }
        Map<String, Object> o = (Map<String, Object>) root;
        int schemaVersion = requireInt(o, "schemaVersion", violations);
        if (schemaVersion > RerankerConfigurationDocument.CURRENT_SCHEMA_VERSION) {
            violations.add("schemaVersion " + schemaVersion + " is newer than supported "
                    + RerankerConfigurationDocument.CURRENT_SCHEMA_VERSION);
        } else if (schemaVersion < 1) {
            violations.add("schemaVersion must be >= 1 (was " + schemaVersion + ")");
        }
        long revision = requireLong(o, "configurationRevision", violations);

        Map<String, Object> d = requireObject(o, "descriptor", violations);
        RerankerProvider provider = null;
        String baseUrl = "";
        String modelName = "";
        List<RerankerCapability> capabilities = new ArrayList<RerankerCapability>();
        RerankerScoreSemantics scoreSemantics = null;
        long timeout = 0;
        RerankerSelectionConfiguration selection = null;
        if (d != null) {
            provider = enumValue(RerankerProvider.class, requireString(d, "provider", violations),
                    "provider", violations);
            baseUrl = requireString(d, "baseUrl", violations);
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                violations.add("descriptor.baseUrl must be an http(s) URL");
            }
            modelName = requireString(d, "modelName", violations);
            if (modelName.trim().isEmpty()) {
                violations.add("descriptor.modelName must not be blank");
            }
            for (String cap : requireStringArray(d, "capabilities", violations)) {
                RerankerCapability capability = enumValue(RerankerCapability.class, cap,
                        "capabilities[]", violations);
                if (capability != null) {
                    capabilities.add(capability);
                }
            }
            if (!capabilities.contains(RerankerCapability.RERANK)) {
                violations.add("descriptor.capabilities must include RERANK");
            }
            scoreSemantics = enumValue(RerankerScoreSemantics.class,
                    requireString(d, "scoreSemantics", violations), "scoreSemantics", violations);
            timeout = requireLong(d, "requestTimeoutMillis", violations);
            if (timeout <= 0) {
                violations.add("descriptor.requestTimeoutMillis must be > 0 (was " + timeout + ")");
            }
            selection = selection(requireObject(d, "selection", violations), violations);
        }

        if (!violations.isEmpty() || provider == null || scoreSemantics == null
                || selection == null) {
            return RerankerConfigurationValidationResult.invalid(violations);
        }
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(provider, baseUrl,
                modelName, capabilities, scoreSemantics, timeout, selection);
        return RerankerConfigurationValidationResult.valid(
                new RerankerConfigurationDocument(schemaVersion, revision, descriptor));
    }

    private static RerankerSelectionConfiguration selection(Map<String, Object> s,
                                                            List<String> violations) {
        if (s == null) {
            return null;
        }
        int maximum = requireInt(s, "maximumSelectedCandidates", violations);
        if (maximum < 1) {
            violations.add("selection.maximumSelectedCandidates must be >= 1 (was " + maximum + ")");
        }
        return new RerankerSelectionConfiguration(maximum,
                optionalDouble(s, "absoluteMinimumScore", violations),
                optionalDouble(s, "minimumTopScoreMargin", violations),
                optionalDouble(s, "maximumScoreDropFromBest", violations));
    }

    // ------------------------------------------------------------------ typed getters

    private static Map<String, Object> requireObject(Map<String, Object> o, String key,
                                                     List<String> violations) {
        Object value = o.get(key);
        if (!(value instanceof Map)) {
            violations.add("field '" + key + "' must be a JSON object");
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static String requireString(Map<String, Object> o, String key, List<String> violations) {
        Object value = o.get(key);
        if (!(value instanceof String)) {
            violations.add("field '" + key + "' must be a string");
            return "";
        }
        return (String) value;
    }

    private static int requireInt(Map<String, Object> o, String key, List<String> violations) {
        return (int) requireLong(o, key, violations);
    }

    private static long requireLong(Map<String, Object> o, String key, List<String> violations) {
        Object value = o.get(key);
        if (!(value instanceof Double)) {
            violations.add("field '" + key + "' must be a number");
            return 0;
        }
        return (long) (double) (Double) value;
    }

    private static List<String> requireStringArray(Map<String, Object> o, String key,
                                                   List<String> violations) {
        List<String> out = new ArrayList<String>();
        Object value = o.get(key);
        if (!(value instanceof List)) {
            violations.add("field '" + key + "' must be an array");
            return out;
        }
        for (Object element : (List<?>) value) {
            if (!(element instanceof String)) {
                violations.add("field '" + key + "' must contain only strings");
            } else {
                out.add((String) element);
            }
        }
        return out;
    }

    private static OptionalDouble optionalDouble(Map<String, Object> o, String key,
                                                 List<String> violations) {
        Object value = o.get(key);
        if (value == null) {
            return OptionalDouble.empty();
        }
        if (!(value instanceof Double)) {
            violations.add("optional field '" + key + "' must be a number when present");
            return OptionalDouble.empty();
        }
        double d = (Double) value;
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            violations.add("optional field '" + key + "' must be finite");
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(d);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String where,
                                                   List<String> violations) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            violations.add("field '" + where + "' has unknown value '" + value + "'");
            return null;
        }
    }

    // ------------------------------------------------------------------ writer primitives

    private static StringBuilder str(StringBuilder sb, String key, String value) {
        return sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    private static StringBuilder num(StringBuilder sb, String key, long value) {
        return sb.append('"').append(key).append("\":").append(value);
    }

    private static void optional(StringBuilder sb, String key, OptionalDouble value) {
        if (value != null && value.isPresent()) {
            sb.append(",\"").append(key).append("\":").append(value.getAsDouble());
        }
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
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
