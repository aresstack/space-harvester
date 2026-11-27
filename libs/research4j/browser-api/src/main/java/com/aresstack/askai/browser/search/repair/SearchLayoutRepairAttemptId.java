package com.aresstack.askai.browser.search.repair;

/**
 * The opaque handle for one bounded repair attempt held in the sidecar cache. It ties a
 * {@link SearchLayoutRepairRequest} to the exact cached {@code RenderedPageDocument} it was captured
 * from, so a submission can only ever be applied to that one snapshot and exactly once.
 */
public final class SearchLayoutRepairAttemptId {

    public final String value;

    public SearchLayoutRepairAttemptId(String value) {
        this.value = value == null ? "" : value;
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SearchLayoutRepairAttemptId
                && ((SearchLayoutRepairAttemptId) other).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
