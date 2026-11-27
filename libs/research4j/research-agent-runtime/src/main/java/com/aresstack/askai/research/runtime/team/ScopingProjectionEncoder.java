package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.loop.ResearchRunWire;
import com.aresstack.askai.research.runtime.loop.ScopingProjectionSuggestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a scoping output into the display-only projection wire line for the UI (RA-P6 §1/§10): the search
 * suggestions + advisory advice — the phase ACTIONS. Phase-isolated like {@link ScopingBriefSource}: only a
 * {@link ScopingAssistantOutput} projects; any other phase's output yields {@code null}. The research brief
 * (the phase artifact) and any visualization are NOT carried here — each has its own path.
 */
public final class ScopingProjectionEncoder {

    private ScopingProjectionEncoder() {
    }

    /** The projection wire line for this output, or {@code null} when the phase produces no scoping projection. */
    public static String wireLineFor(String phaseId, PhaseAssistantOutput output) {
        if (!(output instanceof ScopingAssistantOutput)) {
            return null;
        }
        ScopingAssistantOutput scoping = (ScopingAssistantOutput) output;
        List<ScopingProjectionSuggestion> suggestions = new ArrayList<ScopingProjectionSuggestion>();
        for (SearchSuggestion suggestion : scoping.getSearchSuggestions()) {
            suggestions.add(new ScopingProjectionSuggestion(
                    suggestion.getQuery(), suggestion.getPurpose(), suggestion.getPriority()));
        }
        return ResearchRunWire.scopingProjection(
                phaseId,
                suggestions,
                scoping.getAdvice().getRecommendation().name(),
                scoping.getAdvice().getReason());
    }
}
