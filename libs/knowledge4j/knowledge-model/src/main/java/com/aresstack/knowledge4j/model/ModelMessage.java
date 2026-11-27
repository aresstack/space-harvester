package com.aresstack.knowledge4j.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One message of a chat exchange through the {@link ModelPort} (ADR 0001, 3.2). Immutable. Four roles:
 *
 * <ul>
 *   <li>{@link Role#SYSTEM}: the role prompt;</li>
 *   <li>{@link Role#USER}: user or caller input;</li>
 *   <li>{@link Role#ASSISTANT}: an earlier model turn, replayed as history; it may carry the tool calls the
 *       model made in that turn and, optionally, its reasoning text;</li>
 *   <li>{@link Role#TOOL}: the result of one tool call, naming the tool (and the call id when the provider
 *       assigned one) so the model can match it to its request.</li>
 * </ul>
 */
public final class ModelMessage {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    private final Role role;
    private final String content;
    private final String thinking;
    private final List<ToolCall> toolCalls;
    private final String toolName;
    private final String toolCallId;
    private final List<String> images;

    private ModelMessage(Role role, String content, String thinking, List<ToolCall> toolCalls,
                         String toolName, String toolCallId) {
        this(role, content, thinking, toolCalls, toolName, toolCallId, null);
    }

    private ModelMessage(Role role, String content, String thinking, List<ToolCall> toolCalls,
                         String toolName, String toolCallId, List<String> images) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.thinking = thinking == null ? "" : thinking;
        this.toolCalls = toolCalls == null || toolCalls.isEmpty()
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.toolName = toolName == null ? "" : toolName;
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.images = images == null || images.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(images));
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(Role.SYSTEM, content, null, null, null, null);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(Role.USER, content, null, null, null, null);
    }

    /** A user turn with base64-encoded images (no data-URI prefix), for vision models. */
    public static ModelMessage user(String content, List<String> images) {
        if (images != null) {
            for (String image : images) {
                if (image == null || image.isEmpty()) {
                    throw new IllegalArgumentException("images must not contain null or empty entries");
                }
            }
        }
        return new ModelMessage(Role.USER, content, null, null, null, null, images);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage(Role.ASSISTANT, content, null, null, null, null);
    }

    /**
     * An assistant turn replayed with the tool calls it made and, optionally, its reasoning. Whether the
     * reasoning is sent back to the model is the implementation's decision per provider.
     */
    public static ModelMessage assistant(String content, String thinking, List<ToolCall> toolCalls) {
        if (toolCalls != null) {
            for (ToolCall call : toolCalls) {
                if (call == null) {
                    throw new IllegalArgumentException("tool calls must not contain null");
                }
            }
        }
        return new ModelMessage(Role.ASSISTANT, content, thinking, toolCalls, null, null);
    }

    /**
     * The result of one tool call.
     *
     * @param toolName   the called tool's name; non-blank (Ollama needs it as {@code tool_name})
     * @param toolCallId the provider's call id from {@link ToolCall#getId()}, or null/"" when none
     * @param content    the result text the model reads (typically the tool's JSON or text result)
     */
    public static ModelMessage toolResult(String toolName, String toolCallId, String content) {
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("tool result must name its tool");
        }
        return new ModelMessage(Role.TOOL, content, null, null, toolName, toolCallId);
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    /** Reasoning of a replayed assistant turn; "" otherwise. */
    public String getThinking() {
        return thinking;
    }

    /** Tool calls of a replayed assistant turn; empty otherwise. */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /** The tool a {@link Role#TOOL} message answers; "" for other roles. */
    public String getToolName() {
        return toolName;
    }

    /** The provider call id a {@link Role#TOOL} message answers; "" when none. */
    public String getToolCallId() {
        return toolCallId;
    }

    /** Base64-encoded images of a {@link Role#USER} message; empty otherwise. */
    public List<String> getImages() {
        return images;
    }
}
