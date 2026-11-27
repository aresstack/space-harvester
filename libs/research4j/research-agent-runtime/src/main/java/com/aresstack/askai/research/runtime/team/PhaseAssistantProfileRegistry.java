package com.aresstack.askai.research.runtime.team;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Selects the {@link PhaseAssistantProfile} for the active phase (RA-P6 §6/§48). The active phase is decided
 * only by the application's state machine and reaches the runtime as the live phase id
 * ({@code TeamAgentStateView.getPhaseId()}); this registry maps that id to a profile, falling back to a
 * neutral phase-agnostic profile for phases that do not yet have their own. No big-bang migration: phases
 * gain tailored profiles one at a time, and until then they behave sensibly through the fallback.
 */
public final class PhaseAssistantProfileRegistry {

    /** The existing repository phase id for the first phase; reuse it, do not invent a new taxonomy. */
    public static final String SCOPING_PHASE_ID = "scoping";
    /** The research phase (product name "Sources"). */
    public static final String RESEARCH_PHASE_ID = "research";
    /** The document phase (product name "Document"). */
    public static final String DRAFT_PHASE_ID = "draft";
    /**
     * The legacy finalization phase: persisted old sessions may still report it; it works on the same one
     * canonical document as DRAFT and therefore gets the same prompt.
     */
    public static final String FINALIZATION_PHASE_ID = "finalization";

    /** What every research-phase assistant may read (never a write tool: the user owns every write). */
    private static final String[] RESEARCH_READ_TOOLS = {
            "research_status", "artifact_read", "source_list", "source_review_context", "concept_read"};
    /** The document phases additionally read the canonical document. */
    private static final String[] DRAFT_READ_TOOLS = {
            "research_status", "artifact_read", "source_list", "concept_read", "document_read"};

    private final Map<String, PhaseAssistantProfile> byPhaseId;
    private final PhaseAssistantProfile fallback;

    public PhaseAssistantProfileRegistry(Map<String, PhaseAssistantProfile> profiles,
                                         PhaseAssistantProfile fallback) {
        if (fallback == null) {
            throw new IllegalArgumentException("fallback profile must not be null");
        }
        Map<String, PhaseAssistantProfile> copy = new LinkedHashMap<String, PhaseAssistantProfile>();
        if (profiles != null) {
            for (Map.Entry<String, PhaseAssistantProfile> entry : profiles.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        this.byPhaseId = Collections.unmodifiableMap(copy);
        this.fallback = fallback;
    }

    /**
     * The default registry: a productive SCOPING profile (least privilege — no capabilities/tools yet, writes
     * only the research-brief artifact), the RESEARCH, DRAFT and legacy FINALIZATION profiles (read-only tool
     * subsets, no writable artifact), plus a neutral fallback for every other phase (today OUTLINE).
     */
    public static PhaseAssistantProfileRegistry defaults() {
        return defaults(false);
    }

    /** @param alwaysOfferSuggestions the "Immer Suchvorschläge anbieten" settings checkbox (default off). */
    public static PhaseAssistantProfileRegistry defaults(boolean alwaysOfferSuggestions) {
        return defaults(alwaysOfferSuggestions, false);
    }

    /**
     * @param conceptToolsAvailable whether the HOST offers the Konzeptpapier tools this session
     * (detected from the research MCP tool list at startup) — only then does the scoping prompt
     * carry the book preamble, the concept rules and the {@code conceptAction} field.
     */
    public static PhaseAssistantProfileRegistry defaults(boolean alwaysOfferSuggestions,
                                                         boolean conceptToolsAvailable) {
        PhaseAssistantProfile scoping = new PhaseAssistantProfile(
                SCOPING_PHASE_ID,
                TeamAgentPlaybook.scopingSystemPrompt(alwaysOfferSuggestions, conceptToolsAvailable),
                new ScopingPhaseOutputContract(conceptToolsAvailable),
                "research-brief",
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS);
        PhaseAssistantProfile fallback = new PhaseAssistantProfile(
                "",
                TeamAgentPlaybook.defaultSystemPrompt(),
                new DefaultPhaseOutputContract(),
                "",
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS);
        Map<String, PhaseAssistantProfile> profiles = new LinkedHashMap<String, PhaseAssistantProfile>();
        profiles.put(SCOPING_PHASE_ID, scoping);
        profiles.put(RESEARCH_PHASE_ID, readOnlyProfile(RESEARCH_PHASE_ID,
                TeamAgentPlaybook.researchSystemPrompt(), RESEARCH_READ_TOOLS));
        profiles.put(DRAFT_PHASE_ID, readOnlyProfile(DRAFT_PHASE_ID,
                TeamAgentPlaybook.draftSystemPrompt(), DRAFT_READ_TOOLS));
        profiles.put(FINALIZATION_PHASE_ID, readOnlyProfile(FINALIZATION_PHASE_ID,
                TeamAgentPlaybook.draftSystemPrompt(), DRAFT_READ_TOOLS));
        return new PhaseAssistantProfileRegistry(profiles, fallback);
    }

    /** A phase profile with the generic output contract, the given read tools and no writable artifact. */
    private static PhaseAssistantProfile readOnlyProfile(String phaseId, String systemPrompt, String[] tools) {
        DefaultPhaseOutputContract contract = new DefaultPhaseOutputContract();
        return new PhaseAssistantProfile(phaseId,
                PhaseAssistantProfile.conversationRole(phaseId, systemPrompt, contract, Arrays.asList(tools)),
                contract, "", Collections.<String>emptyList(),
                PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS);
    }

    /** Every registered profile in registration order, the fallback last (the conversation role overview). */
    public java.util.List<PhaseAssistantProfile> all() {
        java.util.List<PhaseAssistantProfile> all = new java.util.ArrayList<PhaseAssistantProfile>(
                byPhaseId.values());
        all.add(fallback);
        return Collections.unmodifiableList(all);
    }

    /**
     * The neutral fallback profile. Also used for the opening GREETING, which is a phase-agnostic bootstrap
     * (there is no topic yet, so no scoping brief can exist) and therefore keeps the generic contract.
     */
    public PhaseAssistantProfile fallback() {
        return fallback;
    }

    /** The profile for {@code phaseId} (case-insensitive), or the neutral fallback when none is registered. */
    public PhaseAssistantProfile forPhase(String phaseId) {
        if (phaseId == null) {
            return fallback;
        }
        PhaseAssistantProfile profile = byPhaseId.get(phaseId.trim().toLowerCase(Locale.ROOT));
        return profile == null ? fallback : profile;
    }
}
