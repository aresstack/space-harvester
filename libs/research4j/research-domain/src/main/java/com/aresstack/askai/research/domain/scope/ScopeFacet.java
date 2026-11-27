package com.aresstack.askai.research.domain.scope;

/**
 * ONE aspect of the investigation area, with an explicit lifecycle. A facet is never silently dropped: a
 * user who first found AR interesting and later calls it a side note does not erase it — the facet is
 * REFINED (status/emphasis change), so the conversation's history stays reconstructable.
 */
public final class ScopeFacet {

    /** Where a facet stands: proposed but unconfirmed, confirmed by the user, or ruled out by the user. */
    public enum Status { PROVISIONAL, CONFIRMED, EXCLUDED }

    private final String facetId;
    private final String label;
    private final Status status;
    private final String rationale;

    public ScopeFacet(String facetId, String label, Status status, String rationale) {
        if (facetId == null || facetId.trim().isEmpty()) {
            throw new IllegalArgumentException("facetId must not be empty");
        }
        this.facetId = facetId.trim();
        this.label = label == null ? "" : label.trim();
        this.status = status == null ? Status.PROVISIONAL : status;
        this.rationale = rationale == null ? "" : rationale.trim();
    }

    /** Stable id — emphases point at THIS, never at the display label. */
    public String getFacetId() {
        return facetId;
    }

    public String getLabel() {
        return label;
    }

    public Status getStatus() {
        return status;
    }

    /** Why it is in (or out) — in the user's terms, so a later reader understands the decision. */
    public String getRationale() {
        return rationale;
    }

    public boolean isExcluded() {
        return status == Status.EXCLUDED;
    }

    /** A refined copy; the facet identity never changes. */
    public ScopeFacet with(Status newStatus, String newRationale) {
        return new ScopeFacet(facetId, label, newStatus == null ? status : newStatus,
                newRationale == null || newRationale.trim().isEmpty() ? rationale : newRationale);
    }
}
