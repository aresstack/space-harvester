package com.aresstack.askai.agent.model.inference;

/**
 * The versioned on-disk structured-inference configuration AskAI publishes per session (the
 * {@code inference-config.json} descriptor): a format version, a monotonically increasing configuration
 * revision (so a later hot-reload can tell a genuinely newer descriptor apart), and the callable
 * {@link InferenceEndpointDescriptor}. Sekret-free.
 */
public final class InferenceConfigurationDocument {

    /** The current descriptor format version this build writes and can read. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    public final int formatVersion;
    public final long configurationRevision;
    public final InferenceEndpointDescriptor descriptor;

    public InferenceConfigurationDocument(int formatVersion, long configurationRevision,
                                          InferenceEndpointDescriptor descriptor) {
        this.formatVersion = formatVersion;
        this.configurationRevision = configurationRevision;
        this.descriptor = descriptor;
    }

    /** The current-format document for the given revision + endpoint. */
    public static InferenceConfigurationDocument current(long configurationRevision,
                                                         InferenceEndpointDescriptor descriptor) {
        return new InferenceConfigurationDocument(CURRENT_FORMAT_VERSION, configurationRevision, descriptor);
    }

    public String getModel() {
        return descriptor == null ? "" : descriptor.model;
    }
}
