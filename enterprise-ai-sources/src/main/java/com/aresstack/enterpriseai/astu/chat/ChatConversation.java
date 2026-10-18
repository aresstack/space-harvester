package com.aresstack.enterpriseai.astu.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unveränderlicher Stand einer Konversation: optionaler System-Prompt und die geordnete Historie.
 *
 * <p>Die Historie ist providerunabhängig. Der System-Prompt wird getrennt gehalten und steht in
 * {@link #requestMessages()} immer an erster Stelle, auch wenn er nachträglich geändert wird.
 * Änderungen liefern eine neue Instanz.
 */
public final class ChatConversation {

    private final ChatConversationId id;
    private final String systemPrompt;
    private final List<ChatMessage> messages;

    public ChatConversation(ChatConversationId id, String systemPrompt, List<ChatMessage> messages) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        List<ChatMessage> copy = new ArrayList<ChatMessage>(messages);
        for (ChatMessage message : copy) {
            if (message == null) {
                throw new IllegalArgumentException("messages must not contain null");
            }
            if (message.role() == ChatRole.SYSTEM) {
                throw new IllegalArgumentException("system prompt is held separately, not in the history");
            }
        }
        this.id = id;
        this.systemPrompt = normalize(systemPrompt);
        this.messages = Collections.unmodifiableList(copy);
    }

    public static ChatConversation empty(ChatConversationId id) {
        return new ChatConversation(id, null, Collections.<ChatMessage>emptyList());
    }

    public ChatConversationId id() {
        return id;
    }

    /** @return der System-Prompt oder {@code null}, wenn keiner gesetzt ist */
    public String systemPrompt() {
        return systemPrompt;
    }

    /** @return die Historie ohne System-Prompt, in Einfügereihenfolge, unveränderlich */
    public List<ChatMessage> messages() {
        return messages;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public ChatConversation withSystemPrompt(String newSystemPrompt) {
        return new ChatConversation(id, newSystemPrompt, messages);
    }

    public ChatConversation append(ChatMessage message) {
        List<ChatMessage> next = new ArrayList<ChatMessage>(messages.size() + 1);
        next.addAll(messages);
        next.add(message);
        return new ChatConversation(id, systemPrompt, next);
    }

    /** @return dieselbe Konversation ohne Historie; der System-Prompt bleibt */
    public ChatConversation cleared() {
        return new ChatConversation(id, systemPrompt, Collections.<ChatMessage>emptyList());
    }

    /** @return System-Prompt (falls gesetzt) gefolgt von der Historie: die Nachrichten für eine Anfrage */
    public List<ChatMessage> requestMessages() {
        if (systemPrompt == null) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<ChatMessage>(messages.size() + 1);
        result.add(ChatMessage.system(systemPrompt));
        result.addAll(messages);
        return Collections.unmodifiableList(result);
    }

    @Override
    public String toString() {
        return "ChatConversation[" + id + ", " + messages.size() + " messages"
                + (systemPrompt == null ? "" : ", system prompt") + "]";
    }

    private static String normalize(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return null;
        }
        return prompt;
    }
}
