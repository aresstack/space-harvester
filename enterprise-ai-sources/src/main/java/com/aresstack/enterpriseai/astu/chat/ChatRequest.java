package com.aresstack.enterpriseai.astu.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Eine providerneutrale Chat-Anfrage: die zu sendenden Nachrichten in Reihenfolge plus Optionen. */
public final class ChatRequest {

    private final List<ChatMessage> messages;
    private final ChatOptions options;
    private final List<ChatToolDefinition> tools;

    public ChatRequest(List<ChatMessage> messages, ChatOptions options) {
        this(messages, options, null);
    }

    /** @param tools Werkzeuge, die das Modell aufrufen darf ({@code null} oder leer: keine) */
    public ChatRequest(List<ChatMessage> messages, ChatOptions options, List<ChatToolDefinition> tools) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("a chat request needs at least one message");
        }
        List<ChatMessage> copy = new ArrayList<ChatMessage>(messages);
        for (ChatMessage message : copy) {
            if (message == null) {
                throw new IllegalArgumentException("messages must not contain null");
            }
        }
        this.messages = Collections.unmodifiableList(copy);
        this.options = options == null ? ChatOptions.defaults() : options;
        this.tools = Collections.unmodifiableList(tools == null ? new ArrayList<ChatToolDefinition>()
                : new ArrayList<ChatToolDefinition>(tools));
    }

    /** Werkzeuge der Anfrage; leer ohne Werkzeuge. */
    public List<ChatToolDefinition> tools() {
        return tools;
    }

    /** @return eine Anfrage aus System-Prompt und Historie der Konversation */
    public static ChatRequest of(ChatConversation conversation, ChatOptions options) {
        return new ChatRequest(conversation.requestMessages(), options);
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public ChatOptions options() {
        return options;
    }

    @Override
    public String toString() {
        return "ChatRequest[" + messages.size() + " messages, " + options
                + (tools.isEmpty() ? "" : ", " + tools.size() + " tools") + "]";
    }
}
