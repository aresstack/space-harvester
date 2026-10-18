package com.aresstack.enterpriseai.astu.chat;

/**
 * Ein Werkzeugaufruf des Modells ({@code tool_calls} einer Assistant-Nachricht): Aufruf-ID, Werkzeugname und die
 * Argumente als JSON-Text, wie das Modell sie geschrieben hat. Die Antwort geht als {@link ChatRole#TOOL}-Nachricht
 * mit derselben ID zurück ({@link ChatMessage#tool(String, String)}).
 */
public final class ChatToolCall {

    private final String id;
    private final String name;
    private final String argumentsJson;

    public ChatToolCall(String id, String name, String argumentsJson) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("tool call name must not be empty");
        }
        this.id = id == null ? "" : id;
        this.name = name;
        this.argumentsJson = argumentsJson == null || argumentsJson.trim().isEmpty() ? "{}" : argumentsJson;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String argumentsJson() {
        return argumentsJson;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChatToolCall)) {
            return false;
        }
        ChatToolCall that = (ChatToolCall) other;
        return id.equals(that.id) && name.equals(that.name) && argumentsJson.equals(that.argumentsJson);
    }

    @Override
    public int hashCode() {
        return (id.hashCode() * 31 + name.hashCode()) * 31 + argumentsJson.hashCode();
    }

    /** Ohne Argumente: sie können Nutzerinhalte tragen. */
    @Override
    public String toString() {
        return "ChatToolCall[" + id + ", " + name + ", " + argumentsJson.length() + " chars]";
    }
}
