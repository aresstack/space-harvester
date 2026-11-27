package com.aresstack.askai.agent.model.port;

/**
 * The required shape of a chat answer (ADR 0001, 3.2 and 4.2: every orchestrator decision is structured
 * output with a schema; code never interprets free text).
 *
 * <ul>
 *   <li>{@link Kind#TEXT}: free text (conversation, narration, synthesis prose).</li>
 *   <li>{@link Kind#JSON}: any JSON value; the provider's generic JSON mode.</li>
 *   <li>{@link Kind#JSON_SCHEMA}: the answer must match the given JSON schema. Providers that support it
 *       (Ollama structured outputs) enforce the schema AT GENERATION TIME through constrained decoding,
 *       which is what keeps small local models from hand-rolling broken JSON. The port does not promise
 *       that every provider enforces every schema keyword, so callers still validate the answer.</li>
 * </ul>
 */
public final class OutputFormat {

    public enum Kind {
        TEXT,
        JSON,
        JSON_SCHEMA
    }

    private static final OutputFormat TEXT = new OutputFormat(Kind.TEXT, "");
    private static final OutputFormat JSON = new OutputFormat(Kind.JSON, "");

    private final Kind kind;
    private final String schemaJson;

    private OutputFormat(Kind kind, String schemaJson) {
        this.kind = kind;
        this.schemaJson = schemaJson;
    }

    public static OutputFormat text() {
        return TEXT;
    }

    public static OutputFormat json() {
        return JSON;
    }

    /**
     * @param schemaJson the JSON schema as a raw JSON object string, forwarded verbatim; non-blank
     */
    public static OutputFormat jsonSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON schema must not be blank");
        }
        return new OutputFormat(Kind.JSON_SCHEMA, schemaJson);
    }

    public Kind getKind() {
        return kind;
    }

    /** The schema for {@link Kind#JSON_SCHEMA}; "" otherwise. */
    public String getSchemaJson() {
        return schemaJson;
    }
}
