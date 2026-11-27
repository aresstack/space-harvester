package com.aresstack.askai.research.domain.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ONE decision about which candidates of a run to look at — an event in its own right, not a property of the
 * run and certainly not of the candidates.
 * <p>
 * A single {@link SearchRun} can carry several of these, and that is the normal case:
 * <pre>
 * Selection #1  DIVERSE_RELEVANT  c2, c7, c18
 * Selection #2  USER_SELECTED     c18, c31      (the user picks after seeing the hits)
 * Selection #3  AGENT_SELECTED    c4, c12       (a later deepening)
 * </pre>
 * None of them writes anything into a {@link SearchCandidate}.
 * <p>
 * A decision may legitimately select NOTHING. The important case is a missing relevance assessment: without
 * it there must be no AUTOMATIC selection (no page may be opened in raw engine order), while the discovered
 * candidates stay untouched and a user or agent can still pick explicitly.
 */
public final class SelectionDecision {

    private final String selectionId;
    private final String searchRunId;
    private final SearchStrategyProfile.CandidateSelection policy;
    private final String profileName;
    private final List<SelectedCandidateRef> selected;
    private final String blockedReason;

    private SelectionDecision(String selectionId, String searchRunId,
                              SearchStrategyProfile.CandidateSelection policy, String profileName,
                              List<SelectedCandidateRef> selected, String blockedReason) {
        if (selectionId == null || selectionId.trim().isEmpty()) {
            throw new IllegalArgumentException("selectionId must not be empty");
        }
        if (searchRunId == null || searchRunId.trim().isEmpty()) {
            throw new IllegalArgumentException("searchRunId must not be empty");
        }
        this.selectionId = selectionId.trim();
        this.searchRunId = searchRunId.trim();
        this.policy = policy == null ? SearchStrategyProfile.CandidateSelection.TOP_RANKED : policy;
        this.profileName = profileName == null ? "" : profileName.trim();
        this.selected = selected == null || selected.isEmpty()
                ? Collections.<SelectedCandidateRef>emptyList()
                : Collections.unmodifiableList(new ArrayList<SelectedCandidateRef>(selected));
        this.blockedReason = blockedReason == null ? "" : blockedReason.trim();
    }

    public static SelectionDecision of(String selectionId, String searchRunId,
                                       SearchStrategyProfile.CandidateSelection policy, String profileName,
                                       List<SelectedCandidateRef> selected) {
        return new SelectionDecision(selectionId, searchRunId, policy, profileName, selected, "");
    }

    /**
     * Nothing was selected AND nothing may be selected automatically — e.g. no relevance assessment. The
     * reason travels with the decision so a caller reports it instead of silently inspecting nothing.
     */
    public static SelectionDecision blocked(String selectionId, String searchRunId,
                                            SearchStrategyProfile.CandidateSelection policy,
                                            String profileName, String reason) {
        return new SelectionDecision(selectionId, searchRunId, policy, profileName,
                Collections.<SelectedCandidateRef>emptyList(), reason);
    }

    public String getSelectionId() {
        return selectionId;
    }

    public String getSearchRunId() {
        return searchRunId;
    }

    public SearchStrategyProfile.CandidateSelection getPolicy() {
        return policy;
    }

    /** The profile this decision was made under — the WHY behind its size and order. */
    public String getProfileName() {
        return profileName;
    }

    /** The picked candidates in the order inspection should work through them. */
    public List<SelectedCandidateRef> getSelected() {
        return selected;
    }

    public List<String> selectedCandidateIds() {
        List<String> ids = new ArrayList<String>();
        for (SelectedCandidateRef ref : selected) {
            ids.add(ref.getCandidateId());
        }
        return Collections.unmodifiableList(ids);
    }

    /** Why nothing may be selected automatically; "" when the decision simply picked nothing. */
    public String getBlockedReason() {
        return blockedReason;
    }

    /** No automatic inspection may follow — never confuse this with "the search found nothing". */
    public boolean isBlocked() {
        return !blockedReason.isEmpty();
    }

    public boolean isEmpty() {
        return selected.isEmpty();
    }

    public String describe() {
        return "selection=" + selectionId + " run=" + searchRunId + " policy=" + policy
                + " picked=" + selected.size() + (isBlocked() ? " blocked=" + blockedReason : "");
    }
}
