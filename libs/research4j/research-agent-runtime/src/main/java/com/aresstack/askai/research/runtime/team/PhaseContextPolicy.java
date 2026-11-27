package com.aresstack.askai.research.runtime.team;

/**
 * How a phase assistant's model context is assembled. This is a forward hook: RA-P6 §10/§11 will make the
 * assembler include only THIS phase's own chat plus the latest relevant artifacts (never other phases' chat
 * histories, never old artifact revisions). Until artifacts and phase-tagged messages exist, the assembler
 * carries the policy without acting on it, so profiles can already declare their intended context shape.
 */
public enum PhaseContextPolicy {

    /** This phase's own chat + the latest relevant artifacts; cross-phase chat is filtered out. */
    OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS
}
