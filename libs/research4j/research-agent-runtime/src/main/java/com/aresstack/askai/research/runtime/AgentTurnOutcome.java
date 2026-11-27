package com.aresstack.askai.research.runtime;

/** How a prompt turn of the {@link ResearchAgentSession} ended (the former ACP stop reason). */
public enum AgentTurnOutcome {
    /** The turn ran to its end; the host releases the composer. */
    END_TURN,
    /** The turn was cancelled by the user (silent on the host side). */
    CANCELLED
}
