package com.aresstack.enterpriseai.astu.chat;

/**
 * Ein Werkzeug, das das Modell in einer Chat-Anfrage aufrufen darf ({@code tools} von {@code /chat/completions}):
 * Name, Beschreibung und das Parameterschema als JSON-Schema-Text (der Adapter reicht es unverändert durch).
 */
public final class ChatToolDefinition {

    private final String name;
    private final String description;
    private final String parametersSchemaJson;

    public ChatToolDefinition(String name, String description, String parametersSchemaJson) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("tool name must not be empty");
        }
        this.name = name.trim();
        this.description = description == null ? "" : description;
        this.parametersSchemaJson = parametersSchemaJson == null || parametersSchemaJson.trim().isEmpty()
                ? "{\"type\":\"object\",\"properties\":{}}" : parametersSchemaJson;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** JSON-Schema der Argumente (Objekt). */
    public String parametersSchemaJson() {
        return parametersSchemaJson;
    }

    @Override
    public String toString() {
        return "ChatToolDefinition[" + name + "]";
    }
}
