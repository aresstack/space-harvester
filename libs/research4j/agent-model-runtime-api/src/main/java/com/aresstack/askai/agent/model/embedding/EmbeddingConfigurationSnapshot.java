package com.aresstack.askai.agent.model.embedding;

/**
 * The published, validated embedding configuration for one session: the neutral callable
 * {@link EmbeddingEndpointDescriptor} plus an optional absolute snapshot file path (for diagnostics / a
 * separate runtime process that reads it). In-JVM consumers use the descriptor directly.
 */
public final class EmbeddingConfigurationSnapshot {

    public final String snapshotPath;
    public final EmbeddingEndpointDescriptor descriptor;

    public EmbeddingConfigurationSnapshot(String snapshotPath, EmbeddingEndpointDescriptor descriptor) {
        this.snapshotPath = snapshotPath == null ? "" : snapshotPath;
        this.descriptor = descriptor;
    }
}
