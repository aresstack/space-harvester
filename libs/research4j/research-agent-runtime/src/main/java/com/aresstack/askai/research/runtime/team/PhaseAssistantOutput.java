package com.aresstack.askai.research.runtime.team;

/**
 * The common surface every phase's structured model output shares, so the {@link ResearchTeamAgent} pipeline
 * can carry a phase-specific output (a generic {@link TeamAgentTurn} or a {@link ScopingAssistantOutput})
 * through the same steps — visible message, repair check, canonical history — WITHOUT the phases sharing one
 * fat output type. Phase-specific fields (research brief, exploration map, search suggestions, …) live only on
 * the concrete type of that phase and are never pushed onto another phase's contract.
 */
public interface PhaseAssistantOutput {

    /** The single line shown to the user (the ONLY part that ever reaches the chat). */
    String getAssistantMessage();

    /** The canonical, round-trippable JSON for this turn's slot in the model history. */
    String canonicalJson();
}
