package com.aresstack.enterpriseai.astu.chat;

/**
 * Vollständige Antwort des Modells: Assistant-Nachricht, Abbruchgrund, Verbrauch und das vom Provider
 * gemeldete Modell. Beim Streaming enthält sie den zusammengesetzten Text aller Deltas.
 */
public final class ChatResponse {

    private final ChatMessage message;
    private final ChatFinishReason finishReason;
    private final ChatUsage usage;
    private final String model;

    public ChatResponse(ChatMessage message, ChatFinishReason finishReason, ChatUsage usage, String model) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (message.role() != ChatRole.ASSISTANT) {
            throw new IllegalArgumentException("a response carries an assistant message");
        }
        this.message = message;
        this.finishReason = finishReason == null ? ChatFinishReason.UNKNOWN : finishReason;
        this.usage = usage == null ? ChatUsage.notReported() : usage;
        this.model = model;
    }

    public ChatMessage message() {
        return message;
    }

    /** @return der Antworttext, Kurzform für {@code message().content()} */
    public String content() {
        return message.content();
    }

    public ChatFinishReason finishReason() {
        return finishReason;
    }

    public ChatUsage usage() {
        return usage;
    }

    /** @return das vom Provider gemeldete Modell oder {@code null} */
    public String model() {
        return model;
    }

    @Override
    public String toString() {
        return "ChatResponse[" + message + ", " + finishReason + ", " + usage + ", model=" + model + "]";
    }
}
