package com.aresstack.askai.research.domain;

/** A user's request to change a confirmed object: the old revision stays, dependents may become STALE. */
public final class ChangeRequest {

    private final String requestId;
    private final String targetId;
    private final String reason;
    private final long requestedAtMillis;

    public ChangeRequest(String requestId, String targetId, String reason, long requestedAtMillis) {
        this.requestId = requestId == null ? "" : requestId;
        this.targetId = targetId == null ? "" : targetId;
        this.reason = reason == null ? "" : reason;
        this.requestedAtMillis = requestedAtMillis;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getReason() {
        return reason;
    }

    public long getRequestedAtMillis() {
        return requestedAtMillis;
    }
}
