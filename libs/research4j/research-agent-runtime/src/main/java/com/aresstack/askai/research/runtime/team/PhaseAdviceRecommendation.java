package com.aresstack.askai.research.runtime.team;

import java.util.Locale;

/**
 * The assistant's ADVISORY read on whether to move on — pure data, never a control signal. RA-P6 §5/§45: the
 * model may recommend, but only an explicit user action moves the workflow. No code may branch on this to
 * trigger a state transition (only display, logging or tests may read it), so a recommendation can never
 * become a workflow gate the way {@code readyForBrief} once did.
 */
public enum PhaseAdviceRecommendation {

    /** The assistant would stay in this phase a little longer. */
    STAY,
    /** The assistant thinks moving on would be reasonable. */
    CONTINUE,
    /** No opinion either way. */
    NEUTRAL;

    /** Parse a recommendation token case-insensitively; anything unknown or missing is {@link #NEUTRAL}. */
    public static PhaseAdviceRecommendation fromToken(String token) {
        if (token == null) {
            return NEUTRAL;
        }
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("STAY")) {
            return STAY;
        }
        if (normalized.equals("CONTINUE")) {
            return CONTINUE;
        }
        return NEUTRAL;
    }
}
