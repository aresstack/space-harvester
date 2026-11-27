package com.aresstack.knowledge4j.access;

/**
 * A search or fetch failed. The message names the reason (unreachable, not found, no permission, denied by the
 * transfer policy) and never carries the requested content or query text.
 */
public class SourceAccessException extends Exception {

    /** Why the access failed, for the visible reason of a skipped step. */
    public enum Reason {
        UNREACHABLE, NOT_FOUND, NO_PERMISSION, DENIED_BY_POLICY, APPROVAL_REQUIRED, FAILED
    }

    private final Reason reason;

    public SourceAccessException(Reason reason, String message) {
        super(message);
        this.reason = reason == null ? Reason.FAILED : reason;
    }

    public SourceAccessException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? Reason.FAILED : reason;
    }

    public Reason reason() {
        return reason;
    }
}
