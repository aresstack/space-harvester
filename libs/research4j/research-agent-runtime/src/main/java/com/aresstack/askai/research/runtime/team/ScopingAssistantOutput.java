package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The SCOPING phase's own structured model output (RA-P6 §29). Kept strictly separate from the generic
 * {@link TeamAgentTurn} so scoping-only fields never leak into the contract Outline/Evidence/Drafting must
 * carry. In this slice it is parsed, validated and made round-trippable; nothing yet persists a brief, renders
 * a map or runs a search.
 * <ul>
 *   <li>{@link #getAssistantMessage()} — the visible reply (required);</li>
 *   <li>{@link #getResearchBriefMarkdown()} — the continuously-maintained research brief, the PRIMARY
 *       artifact of this phase (required, may be a very short first draft after a one-word topic);</li>
 *   <li>{@link #getSearchSuggestions()} — engine-facing queries, kept apart from the research question;</li>
 *   <li>{@link #getAdvice()} — an ADVISORY stay/continue recommendation with no workflow effect.</li>
 * </ul>
 *
 * <p>The scoping agent works on the research brief and search suggestions — NOT on visualization. Any
 * exploration-map/diagram is the job of a separate artifact visualizer, so a model that cannot draw can never
 * ruin a scoping turn.</p>
 */
public final class ScopingAssistantOutput implements PhaseAssistantOutput {

    private final String assistantMessage;
    private final String researchBriefMarkdown;
    private final List<SearchSuggestion> searchSuggestions;
    private final PhaseAdvice advice;
    /** What this turn proposes to CHANGE about the host-held scope; null when it proposes nothing. */
    private final ScopeUpdateDocument scopeUpdate;
    /** The ONE concept tool step of this inference (Konzeptpapier), or null. */
    private final ConceptAction conceptAction;
    /** Why a present conceptAction was malformed (goes back to the model), or null. */
    private final String conceptActionError;
    /** The model EXPLICITLY chose type "none" (observable in the trace) vs. an absent field. */
    private final boolean conceptActionExplicitNone;

    public ScopingAssistantOutput(String assistantMessage, String researchBriefMarkdown,
                                  List<SearchSuggestion> searchSuggestions, PhaseAdvice advice) {
        this(assistantMessage, researchBriefMarkdown, searchSuggestions, advice, null);
    }

    public ScopingAssistantOutput(String assistantMessage, String researchBriefMarkdown,
                                  List<SearchSuggestion> searchSuggestions, PhaseAdvice advice,
                                  ScopeUpdateDocument scopeUpdate) {
        this(assistantMessage, researchBriefMarkdown, searchSuggestions, advice, scopeUpdate,
                ConceptAction.Parsed.absent());
    }

    public ScopingAssistantOutput(String assistantMessage, String researchBriefMarkdown,
                                  List<SearchSuggestion> searchSuggestions, PhaseAdvice advice,
                                  ScopeUpdateDocument scopeUpdate,
                                  ConceptAction.Parsed conceptAction) {
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            throw new IllegalArgumentException("assistantMessage must not be blank");
        }
        // Only the visible answer is required. A brief and search suggestions are OPTIONAL per turn: the
        // structured scope (ScopePatch -> ResearchScopeDraft) is the truth, a brief is a projection of it,
        // and demanding a suggestion every turn would train the assistant to invent searches nobody asked
        // for just to satisfy a parser. "Bibliotheken ist noch sehr breit — geht es dir um die Institution
        // oder die Nutzung?" is a complete, valid scoping turn.
        this.assistantMessage = assistantMessage.trim();
        this.researchBriefMarkdown = researchBriefMarkdown == null ? "" : researchBriefMarkdown.trim();
        this.searchSuggestions = searchSuggestions == null
                ? Collections.<SearchSuggestion>emptyList()
                : Collections.unmodifiableList(new ArrayList<SearchSuggestion>(searchSuggestions));
        this.advice = advice == null ? PhaseAdvice.neutral() : advice;
        this.scopeUpdate = scopeUpdate;
        this.conceptAction = conceptAction == null ? null : conceptAction.getAction();
        this.conceptActionError = conceptAction == null ? null : conceptAction.getError();
        this.conceptActionExplicitNone = conceptAction != null && conceptAction.isExplicitNone();
    }

    /** Whether the model EXPLICITLY answered type "none" (vs. omitting the field). */
    public boolean isConceptActionExplicitNone() {
        return conceptActionExplicitNone;
    }

    /** The ONE concept tool step this inference requests, or {@code null} (a finished turn). */
    public ConceptAction getConceptAction() {
        return conceptAction;
    }

    /** The malformed-action reason (fed back as a rejection), or {@code null}. */
    public String getConceptActionError() {
        return conceptActionError;
    }

    /** The proposed scope changes, or {@code null} when this turn changes nothing about the scope. */
    public ScopeUpdateDocument getScopeUpdate() {
        return scopeUpdate;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public String getResearchBriefMarkdown() {
        return researchBriefMarkdown;
    }

    public List<SearchSuggestion> getSearchSuggestions() {
        return searchSuggestions;
    }

    public PhaseAdvice getAdvice() {
        return advice;
    }

    public String canonicalJson() {
        return ScopingAssistantOutputCodec.toJson(this);
    }
}
