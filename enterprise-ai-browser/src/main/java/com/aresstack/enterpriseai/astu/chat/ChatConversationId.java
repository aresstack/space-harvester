package com.aresstack.enterpriseai.astu.chat;

import java.util.UUID;

/** Identität einer Chat-Konversation (Session). */
public final class ChatConversationId {

    private final String value;

    public ChatConversationId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("conversation id must not be blank");
        }
        this.value = value;
    }

    public static ChatConversationId random() {
        return new ChatConversationId(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ChatConversationId && value.equals(((ChatConversationId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
