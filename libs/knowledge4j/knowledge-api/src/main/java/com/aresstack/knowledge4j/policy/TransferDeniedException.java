package com.aresstack.knowledge4j.policy;

/** A transfer was not allowed. The message names the exit and the reason, never the payload. */
public final class TransferDeniedException extends Exception {

    private final TransferTarget target;
    private final PayloadKind kind;
    private final TransferDecision decision;

    public TransferDeniedException(TransferTarget target, PayloadKind kind, TransferDecision decision) {
        super(kind + " to " + target.id() + " " + decision.reason()
                + (decision.detail().isEmpty() ? "" : ": " + decision.detail()));
        this.target = target;
        this.kind = kind;
        this.decision = decision;
    }

    public TransferTarget target() {
        return target;
    }

    public PayloadKind kind() {
        return kind;
    }

    public TransferDecision decision() {
        return decision;
    }
}
