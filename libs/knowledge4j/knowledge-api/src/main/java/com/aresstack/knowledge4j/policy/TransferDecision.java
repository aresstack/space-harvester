package com.aresstack.knowledge4j.policy;

/**
 * Allowed or denied, never "ask now": a step that needs the user's approval is denied with
 * {@link Reason#APPROVAL_REQUIRED}, and the host collects such steps and asks outside any held model reservation
 * and outside worker threads. An approval in a dialog never lifts a binding ban.
 */
public record TransferDecision(Reason reason, String detail) {

    public enum Reason {
        ALLOWED,
        /** A binding rule forbids the transfer. */
        FORBIDDEN,
        /** Only the user may allow it; the host asks later, the step is skipped visibly until then. */
        APPROVAL_REQUIRED
    }

    private static final TransferDecision ALLOW = new TransferDecision(Reason.ALLOWED, "");

    public TransferDecision {
        reason = reason == null ? Reason.FORBIDDEN : reason;
        detail = detail == null ? "" : detail;
    }

    public static TransferDecision allow() {
        return ALLOW;
    }

    public static TransferDecision forbid(String detail) {
        return new TransferDecision(Reason.FORBIDDEN, detail);
    }

    public static TransferDecision approvalRequired(String detail) {
        return new TransferDecision(Reason.APPROVAL_REQUIRED, detail);
    }

    public boolean allowed() {
        return reason == Reason.ALLOWED;
    }
}
