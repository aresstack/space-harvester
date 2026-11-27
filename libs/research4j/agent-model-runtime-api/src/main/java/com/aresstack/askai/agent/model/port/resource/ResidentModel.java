package com.aresstack.askai.agent.model.port.resource;

import java.time.Instant;

/**
 * One model the endpoint reports as LOADED (ADR 0001, 4.5, residency). For Ollama the source is
 * {@code GET /api/ps}: {@code size}, {@code size_vram}, {@code expires_at}, {@code context_length}.
 *
 * <p>What this view does NOT say, because the provider does not report it: the KV-cache share of the size
 * and the number of parallel slots (the loaded size includes the contexts of all slots). Residency is an
 * observation, not a promise: the provider may unload a model under memory pressure before
 * {@code expiresAt}, and its eviction order is internal and version-dependent. Any scheduling built on this
 * view is therefore advisory.</p>
 */
public final class ResidentModel {

    private final String endpointId;
    private final ModelHandle model;
    private final long sizeBytes;
    private final long vramBytes;
    private final Instant expiresAt;
    private final int contextLength;

    /**
     * @param sizeBytes     loaded size in bytes, or -1 when not reported
     * @param vramBytes     part of it in GPU memory, or -1 when not reported
     * @param expiresAt     when the provider plans to unload it, or null when not reported / no expiry
     * @param contextLength the loaded context length in tokens, or -1 when not reported
     */
    public ResidentModel(String endpointId, ModelHandle model, long sizeBytes, long vramBytes,
                         Instant expiresAt, int contextLength) {
        if (endpointId == null || endpointId.trim().isEmpty()) {
            throw new IllegalArgumentException("endpoint id must not be blank");
        }
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        this.endpointId = endpointId;
        this.model = model;
        this.sizeBytes = sizeBytes < 0 ? -1 : sizeBytes;
        this.vramBytes = vramBytes < 0 ? -1 : vramBytes;
        this.expiresAt = expiresAt;
        this.contextLength = contextLength < 0 ? -1 : contextLength;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public ModelHandle getModel() {
        return model;
    }

    /** Loaded size in bytes (contexts of all slots included), or -1. */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** Bytes in GPU memory, or -1. {@code vram < size} means the model is partly offloaded to the CPU. */
    public long getVramBytes() {
        return vramBytes;
    }

    /** Planned unload time, or null; not a guarantee (see class doc). */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Loaded context length in tokens, or -1. */
    public int getContextLength() {
        return contextLength;
    }
}
