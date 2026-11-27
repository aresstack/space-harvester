package com.aresstack.askai.research.domain.search;

/**
 * How a post-search review ENDED. "Finished" alone is not an outcome: the review that produced a summary and
 * the review whose model call failed both end, and the host has to tell them apart — only the first one may
 * move the reviewed-through watermark forward, and only the other one may be offered again.
 */
public enum PostSearchReviewOutcome {

    /** The review produced its result; the sources it saw count as reviewed. */
    SUCCEEDED,

    /** The model turn failed (unavailable, unusable, rejected). The sources stay unreviewed and retryable. */
    FAILED,

    /** The user stopped the review. Same watermark consequence as a failure: nothing was reviewed. */
    CANCELLED;

    public String token() {
        return name();
    }

    /**
     * @return the outcome for a wire token; an absent or unknown token is {@link #FAILED} — a review that
     *         cannot state that it succeeded did not succeed, and the safe consequence is "still to review"
     *         rather than a silently advanced watermark.
     */
    public static PostSearchReviewOutcome fromToken(String token) {
        if (token != null) {
            for (PostSearchReviewOutcome outcome : values()) {
                if (outcome.name().equalsIgnoreCase(token.trim())) {
                    return outcome;
                }
            }
        }
        return FAILED;
    }

    public boolean isSuccess() {
        return this == SUCCEEDED;
    }
}
