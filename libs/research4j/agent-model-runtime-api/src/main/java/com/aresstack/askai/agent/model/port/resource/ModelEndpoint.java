package com.aresstack.askai.agent.model.port.resource;

/**
 * One endpoint of the platform's ENDPOINT POOL (ADR 0001, 4.5): a local Ollama, the local model runtime,
 * later a cloud endpoint. Every endpoint has a GPU memory budget and serves a set of models; model slots
 * are mapped to endpoints by the platform's configuration.
 *
 * <p>The id is a stable settings key, the label a human-readable name for diagnostics (never a URL with
 * credentials). The memory budget is a setting of the endpoint ({@code -1} = not configured, which
 * disables the scheduler's memory planning for it, never the endpoint).</p>
 */
public final class ModelEndpoint {

    /** Where the endpoint runs; informs the scheduler, never the caller's logic. */
    public enum Kind {
        LOCAL,
        REMOTE
    }

    private final String id;
    private final String label;
    private final Kind kind;
    private final long memoryBudgetBytes;
    private final boolean reachable;

    /**
     * @param id                stable endpoint id; non-blank
     * @param label             display label; null becomes the id
     * @param kind              local or remote; non-null
     * @param memoryBudgetBytes GPU memory budget in bytes, or -1 when not configured
     * @param reachable         whether the endpoint answered its last health probe
     */
    public ModelEndpoint(String id, String label, Kind kind, long memoryBudgetBytes, boolean reachable) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("endpoint id must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("endpoint kind must not be null");
        }
        this.id = id;
        this.label = label == null || label.trim().isEmpty() ? id : label;
        this.kind = kind;
        this.memoryBudgetBytes = memoryBudgetBytes < 0 ? -1 : memoryBudgetBytes;
        this.reachable = reachable;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public Kind getKind() {
        return kind;
    }

    /** GPU memory budget in bytes, or -1 when not configured. */
    public long getMemoryBudgetBytes() {
        return memoryBudgetBytes;
    }

    public boolean isReachable() {
        return reachable;
    }
}
