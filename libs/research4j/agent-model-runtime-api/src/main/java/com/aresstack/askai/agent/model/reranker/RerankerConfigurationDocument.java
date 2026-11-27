package com.aresstack.askai.agent.model.reranker;

/**
 * The versioned, serialized form of a reranker configuration: a schema version, a monotonic
 * configuration revision and one {@link RerankerEndpointDescriptor}. The AskAI host writes it
 * atomically as an immutable start snapshot; the research agent reads it once at start-up (no live
 * reconfiguration). The {@link RerankerEndpointDescriptorCodec} turns it to and from JSON.
 */
public final class RerankerConfigurationDocument {

    /** v1 = A5 reranker endpoint descriptor + selection policy. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public final int schemaVersion;
    public final long configurationRevision;
    public final RerankerEndpointDescriptor descriptor;

    public RerankerConfigurationDocument(int schemaVersion, long configurationRevision,
                                         RerankerEndpointDescriptor descriptor) {
        this.schemaVersion = schemaVersion;
        this.configurationRevision = configurationRevision;
        this.descriptor = descriptor;
    }

    /** A current-schema document wrapping the descriptor. */
    public static RerankerConfigurationDocument current(long configurationRevision,
                                                        RerankerEndpointDescriptor descriptor) {
        return new RerankerConfigurationDocument(CURRENT_SCHEMA_VERSION, configurationRevision,
                descriptor);
    }
}
