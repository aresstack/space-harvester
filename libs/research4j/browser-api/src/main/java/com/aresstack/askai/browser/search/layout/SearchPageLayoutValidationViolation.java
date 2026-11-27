package com.aresstack.askai.browser.search.layout;

/**
 * One concrete reason a raw model layout decision was rejected. The {@link Kind} drives both the
 * retry policy (unknown-id vs schema vs semantic) and the repair prompt; {@link #message} is a short,
 * secret-free, human-readable explanation reused verbatim in the repair prompt and the diagnostics.
 */
public final class SearchPageLayoutValidationViolation {

    /** The category of a violation — maps onto the {@code AiRetryPolicy} retry flags. */
    public enum Kind {
        /** The echoed snapshot id does not match the artifact — a decision for another snapshot. */
        UNKNOWN_SNAPSHOT,
        /** The echoed analysis id does not match the artifact — a decision for another analysis. */
        ANALYSIS_MISMATCH,
        /** A referenced container id is not among the mechanically offered ids (hard invariant). */
        UNKNOWN_CONTAINER_ID,
        /** The same id appears twice within a single list. */
        DUPLICATE_ID,
        /** An id is classified as both organic and excluded. */
        CONTRADICTORY_CLASSIFICATION,
        /** No organic result container was named. */
        NO_ORGANIC_CONTAINER,
        /** Confidence is outside the valid 0..1 range. */
        INVALID_CONFIDENCE,
        /** A configured count limit was exceeded. */
        LIMIT_EXCEEDED,
        /** A root/full-page container was chosen as the organic region without justification. */
        FULL_PAGE_SELECTION,
        /** A result block does not lie inside any chosen organic result region. */
        BLOCK_OUTSIDE_REGION
    }

    public final Kind kind;
    public final String message;

    public SearchPageLayoutValidationViolation(Kind kind, String message) {
        this.kind = kind;
        this.message = message == null ? "" : message;
    }

    /** Schema-shaped violations a repair prompt can plausibly fix by reformatting. */
    public boolean isSchemaViolation() {
        return kind == Kind.UNKNOWN_SNAPSHOT || kind == Kind.ANALYSIS_MISMATCH
                || kind == Kind.DUPLICATE_ID
                || kind == Kind.CONTRADICTORY_CLASSIFICATION || kind == Kind.INVALID_CONFIDENCE
                || kind == Kind.LIMIT_EXCEEDED;
    }

    /** Semantic violations about the CHOICE rather than the shape of the answer. */
    public boolean isSemanticViolation() {
        return kind == Kind.NO_ORGANIC_CONTAINER || kind == Kind.FULL_PAGE_SELECTION
                || kind == Kind.BLOCK_OUTSIDE_REGION;
    }

    @Override
    public String toString() {
        return kind + ": " + message;
    }
}
