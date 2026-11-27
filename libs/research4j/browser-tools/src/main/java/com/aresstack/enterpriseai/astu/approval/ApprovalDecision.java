package com.aresstack.enterpriseai.astu.approval;

/** The answer to one {@link ApprovalRequest}. Only {@link Outcome#APPROVED} or {@link Outcome#APPROVED_ALWAYS} for the same request id runs the tool. */
public final class ApprovalDecision {

    public enum Outcome {
        APPROVED, REJECTED, TIMEOUT, CANCELLED, UNAVAILABLE,
        /** Approved, and the user wants the same answer remembered (only for requests that offer it). */
        APPROVED_ALWAYS,
        /** Rejected, and the user wants the same answer remembered; otherwise behaves like REJECTED. */
        REJECTED_ALWAYS
    }

    private final String requestId;
    private final Outcome outcome;

    public ApprovalDecision(String requestId, Outcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        this.requestId = requestId;
        this.outcome = outcome;
    }

    public static ApprovalDecision of(ApprovalRequest request, Outcome outcome) {
        return new ApprovalDecision(request.id(), outcome);
    }

    public String requestId() {
        return requestId;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Does this decision approve exactly {@code request}? */
    public boolean approves(ApprovalRequest request) {
        return (outcome == Outcome.APPROVED || outcome == Outcome.APPROVED_ALWAYS) && request != null && request.id().equals(requestId);
    }

    @Override
    public String toString() {
        return "ApprovalDecision[" + outcome + "]";
    }
}
