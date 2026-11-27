package com.aresstack.askai.agent.model.nlp;

/**
 * The published, validated NLP configuration for one (capability, language) at session start: the immutable
 * {@link NlpModelDescriptor}. In-JVM consumers use the descriptor directly (its {@code artifactPath}); there is no
 * separate process, so no snapshot file is required.
 */
public final class NlpConfigurationSnapshot {

    private final NlpModelDescriptor descriptor;

    public NlpConfigurationSnapshot(NlpModelDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        this.descriptor = descriptor;
    }

    public NlpModelDescriptor getDescriptor() {
        return descriptor;
    }
}
