package com.aresstack.askai.research.runtime.team;

/**
 * One message in a main-model chat exchange (Ollama {@code /api/chat} {@code messages[]} shape): a role and
 * its text content. Immutable; the TeamAgent keeps a per-session list of these as its conversation history.
 *
 * <p>A history entry also carries the PHASE it was written in and its {@link Kind} (plan PR 17, D7): only the
 * newest own-phase {@code USER} and {@code FINAL} entries enter a prompt; tool rounds, repairs and nudges stay
 * inside their turn. Prompt-only messages (system blocks, instructions) carry no phase and no kind.</p>
 */
public final class ChatMessage {

    /** The Ollama chat roles the TeamAgent uses. */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** What a history entry is (plan D7); {@code null} on prompt-only messages. */
    public enum Kind {
        /** Something the user wrote. */
        USER,
        /** The visible answer of a turn, after its tool rounds collapsed into it. */
        FINAL,
        /** An intermediate answer of a tool round; superseded by the turn's FINAL. */
        TOOL_ROUND,
        /** A parse repair; never stored in the history. */
        REPAIR,
        /** A nudge asking for one action; never stored in the history. */
        NUDGE
    }

    private final Role role;
    private final String content;
    private final String phase;
    private final Kind kind;

    public ChatMessage(Role role, String content) {
        this(role, content, "", null);
    }

    private ChatMessage(Role role, String content, String phase, Kind kind) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        this.role = role;
        this.content = content == null ? "" : content;
        this.phase = phase == null ? "" : phase;
        this.kind = kind;
    }

    /** This message as a history entry of {@code phase} with {@code kind}. */
    public ChatMessage tagged(String phase, Kind kind) {
        return new ChatMessage(role, content, phase, kind);
    }

    /** The phase id the entry was written in; "" for prompt-only messages. */
    public String getPhase() {
        return phase;
    }

    /** The entry's kind; {@code null} for prompt-only messages. */
    public Kind getKind() {
        return kind;
    }

    /** Whether this entry may enter a prompt window of {@code phaseId} (own phase, USER or FINAL). */
    public boolean isWindowEntryOf(String phaseId) {
        return (kind == Kind.USER || kind == Kind.FINAL) && phase.equalsIgnoreCase(phaseId == null ? "" : phaseId);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
