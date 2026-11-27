package com.aresstack.askai.research.runtime.team;

/**
 * The phase-isolation gate for research-brief persistence (RA-P6 §12, test K): ONLY a scoping output carries
 * a research brief, so only a {@link ScopingAssistantOutput} yields markdown to persist. Any other phase's
 * output (a generic {@link TeamAgentTurn}) yields nothing — a non-scoping phase structurally cannot write the
 * {@code research-brief} artifact. Of the scoping fields, only the brief has persistence weight here; the
 * exploration map, search suggestions and advice remain output-only in this slice.
 */
public final class ScopingBriefSource {

    private ScopingBriefSource() {
    }

    /** The research brief markdown to persist for this output, or {@code null} when the phase has none. */
    public static String briefMarkdown(PhaseAssistantOutput output) {
        if (output instanceof ScopingAssistantOutput) {
            return ((ScopingAssistantOutput) output).getResearchBriefMarkdown();
        }
        return null;
    }
}
