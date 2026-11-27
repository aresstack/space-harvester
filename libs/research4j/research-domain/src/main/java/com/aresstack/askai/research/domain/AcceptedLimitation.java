package com.aresstack.askai.research.domain;

/**
 * A consciously accepted evidence limitation ("continue despite gap X"): persisted and carried into the
 * baseline — never a hidden equation of incomplete with sufficient evidence.
 */
public final class AcceptedLimitation {

    private final String limitationId;
    private final String description;
    private final Approval approval;

    public AcceptedLimitation(String limitationId, String description, Approval approval) {
        this.limitationId = limitationId == null ? "" : limitationId;
        this.description = description == null ? "" : description;
        this.approval = approval;
    }

    public String getLimitationId() {
        return limitationId;
    }

    public String getDescription() {
        return description;
    }

    public Approval getApproval() {
        return approval;
    }
}
