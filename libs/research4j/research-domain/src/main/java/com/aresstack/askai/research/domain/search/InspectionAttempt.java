package com.aresstack.askai.research.domain.search;

/**
 * What happened when a candidate's page was actually looked at — ONE attempt, not the candidate's state.
 * <p>
 * Separating this from {@link SearchCandidate} is what makes "deepen this hit later" a normal operation: a
 * candidate skipped under a scan profile can be READ under a research profile, and both attempts stay on
 * record with the policy they ran under. A verdict is always about an attempt, never about the hit itself —
 * a hit that was never looked at is simply a hit, not a skipped one.
 */
public final class InspectionAttempt {

    /** How one attempt ended. */
    public enum Outcome {
        /** The page was successfully read. */
        READ,
        /** Deliberately not opened under the active policy (budget spent, obstacle policy SKIP, transit host). */
        SKIPPED,
        /** An obstacle prevented reading it (consent wall, CAPTCHA, access block). */
        BLOCKED,
        /** Opening/reading was attempted and failed technically. */
        FAILED
    }

    private final String candidateId;
    private final long attemptedAt;
    private final String policy;
    private final Outcome outcome;
    private final String sourceId;
    private final String detail;

    public InspectionAttempt(String candidateId, long attemptedAt, String policy, Outcome outcome,
                             String sourceId, String detail) {
        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("candidateId must not be empty");
        }
        this.candidateId = candidateId.trim();
        this.attemptedAt = attemptedAt;
        this.policy = policy == null ? "" : policy.trim();
        this.outcome = outcome == null ? Outcome.FAILED : outcome;
        this.sourceId = sourceId == null ? "" : sourceId.trim();
        this.detail = detail == null ? "" : detail.trim();
    }

    public String getCandidateId() {
        return candidateId;
    }

    /** Epoch millis — supplied by the caller; the domain never reads a clock. */
    public long getAttemptedAt() {
        return attemptedAt;
    }

    /** The profile/preset this attempt ran under, so a later reader knows WHY it ended this way. */
    public String getPolicy() {
        return policy;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** The source this attempt produced, or "" — the traceable candidate → source link. */
    public String getSourceId() {
        return sourceId;
    }

    /** The concrete reason (obstacle kind, error) in plain words; "" when there is nothing to add. */
    public String getDetail() {
        return detail;
    }

    public boolean isRead() {
        return outcome == Outcome.READ;
    }

    /** Whether trying again under a different policy could plausibly succeed. */
    public boolean isRetryable() {
        return outcome == Outcome.SKIPPED || outcome == Outcome.BLOCKED || outcome == Outcome.FAILED;
    }
}
