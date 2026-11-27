package com.aresstack.askai.research.runtime.browser;

/**
 * What a worker asks for. {@code workerId} and {@code taskId} attribute the user context (diagnostics, the
 * lease's identity). A {@code stateKey} keeps the user context's cookies and local storage across leases:
 * the pool restores the state saved under that key when the lease opens and saves it again when the lease
 * closes. Without a state key the user context is ephemeral and its state is gone with the lease.
 */
public final class BrowserUserContextRequest {

    private final String workerId;
    private final String taskId;
    private final String stateKey;
    private final long acquireTimeoutMillis;

    private BrowserUserContextRequest(String workerId, String taskId, String stateKey, long acquireTimeoutMillis) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("workerId is required");
        }
        this.workerId = workerId.trim();
        this.taskId = taskId == null ? "" : taskId.trim();
        this.stateKey = stateKey == null || stateKey.trim().isEmpty() ? null : stateKey.trim();
        this.acquireTimeoutMillis = Math.max(0L, acquireTimeoutMillis);
    }

    public static BrowserUserContextRequest of(String workerId, String taskId) {
        return new BrowserUserContextRequest(workerId, taskId, null, 0L);
    }

    /** Keep the sign-in state under this key (letters, digits, '.', '_' and '-'). */
    public BrowserUserContextRequest withStateKey(String key) {
        if (key != null && !key.trim().isEmpty() && !key.trim().matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("stateKey must match [A-Za-z0-9._-]{1,64}: " + key);
        }
        return new BrowserUserContextRequest(workerId, taskId, key, acquireTimeoutMillis);
    }

    /** Wait at most this long for capacity; 0 = the pool's configured acquire timeout. */
    public BrowserUserContextRequest withAcquireTimeoutMillis(long millis) {
        return new BrowserUserContextRequest(workerId, taskId, stateKey, millis);
    }

    public String workerId() {
        return workerId;
    }

    public String taskId() {
        return taskId;
    }

    /** The persisted-state key, or null for an ephemeral user context. */
    public String stateKey() {
        return stateKey;
    }

    public long acquireTimeoutMillis() {
        return acquireTimeoutMillis;
    }

    @Override
    public String toString() {
        return "worker=" + workerId + (taskId.isEmpty() ? "" : " task=" + taskId)
                + (stateKey == null ? "" : " state=" + stateKey);
    }
}
