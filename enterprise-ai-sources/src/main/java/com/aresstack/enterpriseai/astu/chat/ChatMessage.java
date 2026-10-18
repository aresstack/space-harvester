package com.aresstack.enterpriseai.astu.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eine unveränderliche Chat-Nachricht: Rolle plus Textinhalt.
 *
 * <p>{@link #toString()} gibt bewusst nur Rolle und Länge aus, damit Nachrichteninhalte nicht
 * versehentlich in Logs landen.
 */
public final class ChatMessage {

    private final ChatRole role;
    private final String content;
    private final List<ChatToolCall> toolCalls;
    private final String toolCallId;
    private final List<ChatImage> images;

    public ChatMessage(ChatRole role, String content) {
        this(role, content, Collections.<ChatToolCall>emptyList(), null);
    }

    private ChatMessage(ChatRole role, String content, List<ChatToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, Collections.<ChatImage>emptyList());
    }

    private ChatMessage(ChatRole role, String content, List<ChatToolCall> toolCalls, String toolCallId,
                        List<ChatImage> images) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.role = role;
        this.content = content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ChatToolCall>(toolCalls));
        this.toolCallId = toolCallId;
        this.images = Collections.unmodifiableList(new ArrayList<ChatImage>(images));
    }

    /** Eine Nutzernachricht mit Bildern (Bildbeschreibung, OCR). */
    public static ChatMessage userWithImages(String content, List<ChatImage> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("images must not be empty");
        }
        return new ChatMessage(ChatRole.USER, content == null ? "" : content,
                Collections.<ChatToolCall>emptyList(), null, images);
    }

    /** Die Bilder einer Nutzernachricht; leer bei allen anderen. */
    public List<ChatImage> images() {
        return images;
    }

    /** Eine Assistant-Nachricht mit Werkzeugaufrufen ({@code tool_calls}); der Text darf leer sein. */
    public static ChatMessage assistantToolCalls(String content, List<ChatToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("tool calls must not be empty");
        }
        return new ChatMessage(ChatRole.ASSISTANT, content == null ? "" : content, calls, null);
    }

    /** Das Ergebnis eines Werkzeugaufrufs ({@code role=tool}) zur Aufruf-ID. */
    public static ChatMessage tool(String toolCallId, String content) {
        if (toolCallId == null) {
            throw new IllegalArgumentException("toolCallId must not be null");
        }
        return new ChatMessage(ChatRole.TOOL, content == null ? "" : content,
                Collections.<ChatToolCall>emptyList(), toolCallId);
    }

    /** Werkzeugaufrufe einer Assistant-Nachricht; leer bei allen anderen. */
    public List<ChatToolCall> toolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** Aufruf-ID einer {@link ChatRole#TOOL}-Nachricht, sonst {@code null}. */
    public String toolCallId() {
        return toolCallId;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    public static ChatMessage developer(String content) {
        return new ChatMessage(ChatRole.DEVELOPER, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content);
    }

    public ChatRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatMessage)) {
            return false;
        }
        ChatMessage that = (ChatMessage) other;
        return role == that.role && content.equals(that.content) && toolCalls.equals(that.toolCalls)
                && (toolCallId == null ? that.toolCallId == null : toolCallId.equals(that.toolCallId))
                && images.equals(that.images);
    }

    @Override
    public int hashCode() {
        return (31 * role.hashCode() + content.hashCode()) * 31 + toolCalls.hashCode();
    }

    @Override
    public String toString() {
        return "ChatMessage[" + role + ", " + content.length() + " chars"
                + (toolCalls.isEmpty() ? "" : ", " + toolCalls.size() + " tool calls")
                + (images.isEmpty() ? "" : ", " + images.size() + " images") + "]";
    }
}
