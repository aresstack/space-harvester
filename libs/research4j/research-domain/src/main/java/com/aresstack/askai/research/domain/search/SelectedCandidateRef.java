package com.aresstack.askai.research.domain.search;

/**
 * ONE candidate picked by ONE selection: a reference, plus why it was picked and in which position.
 * <p>
 * It is a reference on purpose — "selected" is not a state of the hit. The same candidate can be part of a
 * diverse selection today, of an explicit user selection tomorrow, and of neither in a third one; none of
 * that changes the discovery record it points at.
 */
public final class SelectedCandidateRef {

    private final String candidateId;
    private final int ordinal;
    private final String selectionReason;

    public SelectedCandidateRef(String candidateId, int ordinal, String selectionReason) {
        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("candidateId must not be empty");
        }
        this.candidateId = candidateId.trim();
        this.ordinal = Math.max(1, ordinal);
        this.selectionReason = selectionReason == null ? "" : selectionReason.trim();
    }

    public String getCandidateId() {
        return candidateId;
    }

    /** Position within THIS selection (1-based) — the order inspection should work through. */
    public int getOrdinal() {
        return ordinal;
    }

    /** Why this one, in plain words ("rank 2", "new domain among relevant hits", "picked by the user"). */
    public String getSelectionReason() {
        return selectionReason;
    }
}
