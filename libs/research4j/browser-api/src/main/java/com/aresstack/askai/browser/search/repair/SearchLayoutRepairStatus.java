package com.aresstack.askai.browser.search.repair;

/**
 * The typed outcome of applying a validated layout decision to a cached snapshot in the sidecar. The
 * guard statuses ({@link #UNKNOWN_ATTEMPT} … {@link #INVALID_DECISION}) are hard rejections; an
 * applied-but-empty layout is {@link #EXTRACTION_FAILED}, never a fabricated empty engine.
 */
public enum SearchLayoutRepairStatus {
    ORGANIC_RESULTS,
    EXTRACTION_FAILED,
    UNKNOWN_ATTEMPT,
    EXPIRED_ATTEMPT,
    ALREADY_CONSUMED,
    SNAPSHOT_MISMATCH,
    FINGERPRINT_MISMATCH,
    INVALID_DECISION
}
