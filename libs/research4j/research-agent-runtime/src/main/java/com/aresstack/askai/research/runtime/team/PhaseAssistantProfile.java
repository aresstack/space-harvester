package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.role.RoleToolCatalog;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The technically-enclosed assistant of ONE research phase (RA-P6 §6/§7). Rather than a single monolithic
 * system prompt that tries to govern every phase, each phase has its own profile: its own system prompt, the
 * one artifact it may WRITE, the artifacts it may READ, its allowed capabilities (Principle of Least Privilege)
 * and its context policy. The active phase — decided solely by the application's state machine — selects the
 * profile, and the profile decides the prompt and (later) the tools and readable/writable artifacts.
 *
 * <p>Since ADR 0001 lane D a profile runs a {@link ResearchRole}: the role carries the system prompt, the
 * tool subset (the allowed capabilities), the output shape, the model slot and the context size. Only
 * {@link #getSystemPrompt()} is consumed productively so far; the artifact ids, the capability set and the
 * {@link PhaseContextPolicy} are carried as declared metadata (forward hooks) so a phase can already state its
 * intended privileges before artifacts, tools and context filtering are wired.</p>
 */
public final class PhaseAssistantProfile {

    private final String phaseId;
    private final ResearchRole role;
    private final PhaseOutputContract outputContract;
    private final String writableArtifactId;
    private final List<String> readableArtifactIds;
    private final Set<String> allowedCapabilities;
    private final PhaseContextPolicy contextPolicy;

    public PhaseAssistantProfile(String phaseId, String systemPrompt, PhaseOutputContract outputContract,
                                 String writableArtifactId, List<String> readableArtifactIds,
                                 Set<String> allowedCapabilities, PhaseContextPolicy contextPolicy) {
        this(phaseId, conversationRole(phaseId, systemPrompt, outputContract, allowedCapabilities),
                outputContract, writableArtifactId, readableArtifactIds, contextPolicy);
    }

    /**
     * A profile over an explicit {@link ResearchRole} (ADR 0001, 4.1): the role carries the system prompt,
     * the tool subset (which IS the allowed capability set), the output shape, the model slot and the context
     * size; the profile adds what is phase-bound (the contract object, the artifacts, the context policy).
     */
    public PhaseAssistantProfile(String phaseId, ResearchRole role, PhaseOutputContract outputContract,
                                 String writableArtifactId, List<String> readableArtifactIds,
                                 PhaseContextPolicy contextPolicy) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (role.getPromptSource() != ResearchRole.PromptSource.ROLE) {
            throw new IllegalArgumentException("a phase assistant declares its own system prompt");
        }
        if (outputContract == null) {
            throw new IllegalArgumentException("outputContract must not be null");
        }
        this.phaseId = phaseId == null ? "" : phaseId.trim();
        this.role = role;
        this.outputContract = outputContract;
        this.writableArtifactId = writableArtifactId == null ? "" : writableArtifactId.trim();
        this.readableArtifactIds = immutableStrings(readableArtifactIds);
        this.allowedCapabilities = immutableSet(new LinkedHashSet<String>(role.getTools().getToolNames()));
        this.contextPolicy = contextPolicy == null
                ? PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS : contextPolicy;
    }

    /**
     * The conversation role of a phase: role id {@code conversation} (the phase-agnostic fallback) or
     * {@code conversation.<phase>}, the phase's own slot ({@code phase.<id>}; the fallback is bound to Concept
     * and re-bound per turn to the live phase), the model's context, the given tools.
     */
    public static ResearchRole conversationRole(String phaseId, String systemPrompt,
                                                PhaseOutputContract outputContract,
                                                java.util.Collection<String> toolNames) {
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("systemPrompt must not be empty");
        }
        if (outputContract == null) {
            throw new IllegalArgumentException("outputContract must not be null");
        }
        String phase = phaseId == null ? "" : phaseId.trim().toLowerCase(java.util.Locale.ROOT);
        return ResearchRole.builder(phase.isEmpty() ? "conversation" : "conversation." + phase,
                        ResearchModelSlots.phase(phase))
                .purpose(phase.isEmpty()
                        ? "assists the user in a phase without its own profile"
                        : "assists the user in the " + phase + " phase")
                .systemPrompt(systemPrompt)
                .tools(RoleToolCatalog.of(toolNames == null
                        ? Collections.<String>emptyList() : new java.util.ArrayList<String>(toolNames)))
                .output(RoleOutput.text(outputContract.getClass().getSimpleName()))
                .build();
    }

    /** The phase this profile serves (matched case-insensitively against the live host phase id). */
    public String getPhaseId() {
        return phaseId;
    }

    public String getSystemPrompt() {
        return role.getSystemPrompt();
    }

    /** The conversation role this profile runs (prompt, tools, output, slot, context). */
    public ResearchRole getRole() {
        return role;
    }

    /** How this phase's raw model output is parsed/validated — selected together with the system prompt. */
    public PhaseOutputContract getOutputContract() {
        return outputContract;
    }

    /** The single artifact this phase may write (forward hook — not enforced yet). Empty when none. */
    public String getWritableArtifactId() {
        return writableArtifactId;
    }

    /** The artifacts this phase may read (forward hook — not enforced yet). */
    public List<String> getReadableArtifactIds() {
        return readableArtifactIds;
    }

    /** The capabilities/tools this phase's assistant is allowed (forward hook — least privilege, empty now). */
    public Set<String> getAllowedCapabilities() {
        return allowedCapabilities;
    }

    public PhaseContextPolicy getContextPolicy() {
        return contextPolicy;
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        java.util.List<String> copy = new java.util.ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static Set<String> immutableSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(copy);
    }
}
