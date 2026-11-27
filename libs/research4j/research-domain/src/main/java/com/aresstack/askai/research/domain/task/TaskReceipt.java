package com.aresstack.askai.research.domain.task;

/**
 * The receipt of one tool call a worker made while executing the task. The receipt text is kept verbatim in
 * its machine format: it is what the tool CONFIRMED, and the only thing a narration may claim.
 */
public final class TaskReceipt {

    private final String toolName;
    private final boolean succeeded;
    private final String receipt;
    private final long recordedAtMillis;

    public TaskReceipt(String toolName, boolean succeeded, String receipt, long recordedAtMillis) {
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("toolName must not be empty");
        }
        this.toolName = toolName.trim();
        this.succeeded = succeeded;
        this.receipt = receipt == null ? "" : receipt;
        this.recordedAtMillis = recordedAtMillis;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    public String getReceipt() {
        return receipt;
    }

    public long getRecordedAtMillis() {
        return recordedAtMillis;
    }
}
