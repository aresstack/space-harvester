package com.aresstack.enterpriseai.chat.api;

/**
 * Ein Werkzeug, das das Modell aufrufen darf: Name, Beschreibung und JSON-Schema der Argumente (als JSON-Text,
 * damit der Port keine JSON-Bibliothek kennt). Der Adapter schreibt es flach als
 * {@code {"type":"function","name":...,"description":...,"parameters":...}}.
 */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final String parametersSchema;

    private ToolDefinition(String name, String description, String parametersSchema) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (parametersSchema == null || !parametersSchema.trim().startsWith("{")) {
            throw new IllegalArgumentException("parametersSchema must be a JSON object");
        }
        this.name = name.trim();
        this.description = description == null ? "" : description;
        this.parametersSchema = parametersSchema.trim();
    }

    /** Eine Funktion mit JSON-Schema-Objekt {@code parametersSchema}. */
    public static ToolDefinition function(String name, String description, String parametersSchema) {
        return new ToolDefinition(name, description, parametersSchema);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** JSON-Schema der Argumente als JSON-Objekttext. */
    public String parametersSchema() {
        return parametersSchema;
    }

    @Override
    public String toString() {
        return "ToolDefinition[" + name + "]";
    }
}
