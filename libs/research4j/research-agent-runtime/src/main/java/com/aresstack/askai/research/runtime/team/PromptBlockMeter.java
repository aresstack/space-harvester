package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts the named blocks of ONE final model request (plan PR 01): the blocks of {@link PhaseContextAssembler}
 * (profile, date, language, state, fence and concept in Concept, concept summary and phase artifact
 * later, history) and those {@link ResearchTeamAgent} appends (tool
 * feedback, repair note, greeting instruction, user message). It only measures; the request stays unchanged.
 * The block sizes are the characters of the message contents, so their sum equals the characters of the
 * assembled messages.
 */
public final class PromptBlockMeter {

    public static final String PROFILE = "profile";
    public static final String DATE = "date";
    public static final String LANGUAGE = "language";
    public static final String STATE = "state";
    public static final String FENCE = "fence";
    public static final String CONCEPT = "concept";
    /** Later phases only (plan PR 16): the read-only summary of the frozen concept. */
    public static final String CONCEPT_SUMMARY = "concept-summary";
    /** Later phases only (plan PR 16): the artifact block of the live phase with its latest change request. */
    public static final String PHASE_ARTIFACT = "phase-artifact";
    public static final String HISTORY = "history";
    public static final String TOOL_FEEDBACK = "tool-feedback";
    public static final String REPAIR_NOTE = "repair-note";
    public static final String GREETING_INSTRUCTION = "greeting-instruction";
    public static final String USER_MESSAGE = "user-message";
    /** The open turn's user message and latest answer, pinned in its tool rounds (PR 17). */
    public static final String OPEN_TURN = "open-turn";

    /** Rough characters per token for the pre-call estimate; deliberately pessimistic for German text. */
    static final int CHARS_PER_TOKEN = 3;

    private final Map<String, Integer> blocks = new LinkedHashMap<String, Integer>();

    /** Add the characters of a block; a repeated name accumulates. */
    public PromptBlockMeter add(String name, int chars) {
        Integer previous = blocks.get(name);
        blocks.put(name, (previous == null ? 0 : previous) + Math.max(0, chars));
        return this;
    }

    public PromptBlockMeter add(String name, ChatMessage message) {
        return add(name, message == null || message.getContent() == null ? 0 : message.getContent().length());
    }

    public PromptBlockMeter add(String name, List<ChatMessage> messages) {
        int chars = 0;
        if (messages != null) {
            for (ChatMessage message : messages) {
                chars += message.getContent() == null ? 0 : message.getContent().length();
            }
        }
        return add(name, chars);
    }

    /** A copy of this meter, so a repair request can add its blocks on top of the first request's. */
    public PromptBlockMeter copy() {
        PromptBlockMeter copy = new PromptBlockMeter();
        copy.blocks.putAll(blocks);
        return copy;
    }

    /** The blocks in request order with their characters. */
    public Map<String, Integer> blocks() {
        return Collections.unmodifiableMap(blocks);
    }

    public int totalChars() {
        int total = 0;
        for (Integer chars : blocks.values()) {
            total += chars;
        }
        return total;
    }

    /** The pre-call prompt estimate in tokens (characters / {@value #CHARS_PER_TOKEN}, rounded up). */
    public int estimatedTokens() {
        return (totalChars() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Characters of a message list, for the sum check. */
    public static int charsOf(List<ChatMessage> messages) {
        return new PromptBlockMeter().add("all", messages).totalChars();
    }

    /** {@code profile=1200 date=24 ...} in request order. */
    public String render() {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Integer> block : blocks.entrySet()) {
            parts.add(block.getKey() + "=" + block.getValue());
        }
        return String.join(" ", parts);
    }
}
