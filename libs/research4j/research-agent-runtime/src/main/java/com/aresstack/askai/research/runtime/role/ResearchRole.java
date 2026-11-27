package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.ThinkingMode;

import java.util.regex.Pattern;

/**
 * One model role of the research agent (ADR 0001, 4.1: "each = system prompt + tool subset + schema + model
 * slot + context size"). A role is a plugin-side declaration, not an agent object: the code that runs it (the
 * TeamAgent, the scope sweep, the readiness judge, the layout repair, later the orchestrator and the new
 * workers) stays deterministic and reads its prompt, tools, output shape, slot and context from here.
 *
 * <ul>
 *   <li><b>prompt</b>: the role's system prompt, or {@link PromptSource#CALLER} when the request carries it
 *       (the SERP layout repair's prompts are built by {@code browser-search-analysis} per ticket);</li>
 *   <li><b>tools</b>: the {@link RoleToolCatalog} the role may use, bound per session to the host's
 *       {@code McpToolContribution}s;</li>
 *   <li><b>output</b>: the {@link RoleOutput} shape and the contract that reads it;</li>
 *   <li><b>slot</b>: the {@link ModelSlot} the platform resolves to a model on an endpoint (ADR 3.2);</li>
 *   <li><b>context</b>: the role's context size in tokens; {@code 0} means the slot's default, which is a
 *       platform setting per slot (AskAI's model settings), so no role carries a hidden constant.</li>
 * </ul>
 *
 * <p>A role never owns a phase transition or an approval (RA-P6): those stay with the user and the host
 * state machine whatever a role declares.</p>
 */
public final class ResearchRole {

    /** Where the system prompt comes from. */
    public enum PromptSource {
        /** The role declares its system prompt ({@link #getSystemPrompt()}). */
        ROLE,
        /** Each request carries its own prompts; the role declares none. */
        CALLER
    }

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    private final String id;
    private final String purpose;
    private final PromptSource promptSource;
    private final String systemPrompt;
    private final RoleToolCatalog tools;
    private final RoleOutput output;
    private final ModelSlot slot;
    private final int contextTokens;
    private final ThinkingMode thinking;

    private ResearchRole(Builder builder) {
        this.id = builder.id;
        this.purpose = builder.purpose;
        this.promptSource = builder.promptSource;
        this.systemPrompt = builder.systemPrompt;
        this.tools = builder.tools;
        this.output = builder.output;
        this.slot = builder.slot;
        this.contextTokens = builder.contextTokens;
        this.thinking = builder.thinking;
    }

    /**
     * @param id   the role id: lower-case {@code [a-z][a-z0-9._-]{0,63}}, unique within the agent
     * @param slot the model slot the role calls
     */
    public static Builder builder(String id, ModelSlot slot) {
        return new Builder(id, slot);
    }

    public String getId() {
        return id;
    }

    /** One line on what the role decides or produces, for diagnostics and the role overview. */
    public String getPurpose() {
        return purpose;
    }

    public PromptSource getPromptSource() {
        return promptSource;
    }

    /** The role's system prompt; empty when {@link #getPromptSource()} is {@code CALLER}. */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    public RoleToolCatalog getTools() {
        return tools;
    }

    public RoleOutput getOutput() {
        return output;
    }

    public ModelSlot getSlot() {
        return slot;
    }

    /** The role's context size in tokens; 0 = the slot's default from the platform settings. */
    public int getContextTokens() {
        return contextTokens;
    }

    /**
     * The same role with another context size, for a user setting that overrides the slot default for one
     * role. {@code 0} returns to the slot default.
     */
    /**
     * The thinking mode the role sends: {@link ThinkingMode#DEFAULT} leaves it to the slot (Auto), a contract
     * role (judge, planner, verifier, orchestrator, repair) sends {@link ThinkingMode#OFF} (plan 4.1, rule 3).
     */
    public ThinkingMode getThinking() {
        return thinking;
    }

    public ResearchRole withContextTokens(int tokens) {
        return toBuilder().contextTokens(tokens).build();
    }

    /**
     * The same role on another model slot: the conversation role follows the LIVE phase's slot
     * ({@code phase.<id>}, plan 5.1), also when the profile registry falls back to its neutral profile.
     */
    public ResearchRole withSlot(ModelSlot other) {
        if (other == null) {
            throw new IllegalArgumentException("a role needs a model slot");
        }
        if (other.equals(slot)) {
            return this;
        }
        return toBuilder(other).build();
    }

    private Builder toBuilder() {
        return toBuilder(slot);
    }

    private Builder toBuilder(ModelSlot target) {
        Builder copy = new Builder(id, target);
        copy.purpose = purpose;
        copy.promptSource = promptSource;
        copy.systemPrompt = systemPrompt;
        copy.tools = tools;
        copy.output = output;
        copy.contextTokens = contextTokens;
        copy.thinking = thinking;
        return copy;
    }

    @Override
    public String toString() {
        return id + "[slot=" + slot + ", context=" + (contextTokens == 0 ? "model" : "min " + contextTokens)
                + ", tools=" + tools + ", output=" + output + ", prompt=" + promptSource + "]";
    }

    /** Builds a role; the prompt and the output are mandatory. */
    public static final class Builder {

        private final String id;
        private final ModelSlot slot;
        private String purpose = "";
        private PromptSource promptSource;
        private String systemPrompt = "";
        private RoleToolCatalog tools = RoleToolCatalog.none();
        private RoleOutput output;
        private int contextTokens;
        private ThinkingMode thinking = ThinkingMode.DEFAULT;

        private Builder(String id, ModelSlot slot) {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid role id: " + id
                        + " (expected lower-case [a-z][a-z0-9._-]{0,63})");
            }
            if (slot == null) {
                throw new IllegalArgumentException("a role needs a model slot");
            }
            this.id = id;
            this.slot = slot;
        }

        public Builder purpose(String purpose) {
            this.purpose = purpose == null ? "" : purpose.trim();
            return this;
        }

        /** The role's own system prompt, kept verbatim (no trimming: prompts are pinned byte for byte). */
        public Builder systemPrompt(String systemPrompt) {
            if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
                throw new IllegalArgumentException("a role's system prompt must not be blank");
            }
            this.promptSource = PromptSource.ROLE;
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** The requests carry their own prompts. */
        public Builder promptFromCaller() {
            this.promptSource = PromptSource.CALLER;
            this.systemPrompt = "";
            return this;
        }

        public Builder tools(RoleToolCatalog tools) {
            this.tools = tools == null ? RoleToolCatalog.none() : tools;
            return this;
        }

        public Builder output(RoleOutput output) {
            this.output = output;
            return this;
        }

        /** The thinking mode the role sends; null = {@link ThinkingMode#DEFAULT} (the slot's setting). */
        public Builder thinking(ThinkingMode thinking) {
            this.thinking = thinking == null ? ThinkingMode.DEFAULT : thinking;
            return this;
        }

        /** @param contextTokens the minimum context the role needs, 0 for none; otherwise positive */
        public Builder contextTokens(int contextTokens) {
            if (contextTokens < 0) {
                throw new IllegalArgumentException("context tokens must be >= 0");
            }
            this.contextTokens = contextTokens;
            return this;
        }

        public ResearchRole build() {
            if (promptSource == null) {
                throw new IllegalStateException("role " + id + " declares no prompt source");
            }
            if (output == null) {
                throw new IllegalStateException("role " + id + " declares no output");
            }
            return new ResearchRole(this);
        }
    }
}
