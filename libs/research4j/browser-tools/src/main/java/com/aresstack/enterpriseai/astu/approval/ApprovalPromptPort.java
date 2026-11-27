package com.aresstack.enterpriseai.astu.approval;

/**
 * Asks the user to approve one tool call and waits for the answer. Called on the tool thread, never on the UI thread.
 * Implementations return {@link ApprovalDecision.Outcome#TIMEOUT} after {@code timeoutMillis}, and
 * {@link ApprovalDecision.Outcome#UNAVAILABLE} when nobody can be asked.
 */
public interface ApprovalPromptPort {

    /** No confirmation channel: every question ends {@link ApprovalDecision.Outcome#UNAVAILABLE}. */
    static ApprovalPromptPort unavailable() {
        return (request, timeoutMillis) -> ApprovalDecision.of(request, ApprovalDecision.Outcome.UNAVAILABLE);
    }

    ApprovalDecision ask(ApprovalRequest request, long timeoutMillis);
}
