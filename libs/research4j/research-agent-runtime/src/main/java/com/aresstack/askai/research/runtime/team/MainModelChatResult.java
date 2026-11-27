package com.aresstack.askai.research.runtime.team;

/**
 * The typed outcome of one main-model chat call. Success carries the assistant's raw text; every failure is
 * NAMED (transport, timeout, HTTP, empty answer) and never fabricated as a success — the TeamAgent turns a
 * non-OK result into an honest {@code MODEL_UNAVAILABLE} status for the user, offering a retry.
 */
public final class MainModelChatResult {

    public enum Status {
        OK,
        TIMEOUT,
        PROVIDER_FAILURE,
        INVALID_RESPONSE
    }

    private final Status status;
    private final String text;
    private final String detail;
    private final ModelCallMetrics metrics;

    private MainModelChatResult(Status status, String text, String detail, ModelCallMetrics metrics) {
        this.status = status;
        this.metrics = metrics == null ? ModelCallMetrics.unknown() : metrics;
        this.text = text == null ? "" : text;
        this.detail = detail == null ? "" : detail;
    }

    public static MainModelChatResult ok(String text) {
        return new MainModelChatResult(Status.OK, text, "", null);
    }

    /** A success with what the call cost (token usage, finish reason, durations, model label). */
    public static MainModelChatResult ok(String text, ModelCallMetrics metrics) {
        return new MainModelChatResult(Status.OK, text, "", metrics);
    }

    public static MainModelChatResult failure(Status status, String detail) {
        return new MainModelChatResult(status, "", detail, null);
    }

    public static MainModelChatResult failure(Status status, String detail, ModelCallMetrics metrics) {
        return new MainModelChatResult(status, "", detail, metrics);
    }

    /** What the call cost; {@link ModelCallMetrics#unknown()} when the transport reported nothing. */
    public ModelCallMetrics getMetrics() {
        return metrics;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** The assistant's raw text (empty on any failure). */
    public String getText() {
        return text;
    }

    /** True when the answer stopped at its token budget ({@code done_reason} length): a flagged prose result. */
    public boolean isCutOff() {
        return "length".equals(metrics.getDoneReason());
    }

    /** A human-readable, secret-free reason for a non-OK status. */
    public String getDetail() {
        return detail;
    }
}
