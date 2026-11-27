package com.aresstack.askai.agent.model.nlp;

/**
 * The neutral, resolved identity + location of a locally installed NLP model ARTIFACT. Unlike embeddings/reranker
 * (a callable HTTP endpoint on the sidecar), an NLP model is a static file loaded directly by Java, so the
 * descriptor carries the local {@code artifactPath} instead of a URL. The consumer ({@code research-text-opennlp})
 * needs ONLY this descriptor (id, implementation, path) — never the AskAI settings, the model store or a catalog.
 *
 * <p>{@code sha256} + {@code version} + {@code compatibleRuntime} make the artifact self-describing so the host can
 * verify integrity and compatibility; the artifact-path naming (e.g. {@code sentence-de.bin}) is NEVER the identity.</p>
 */
public final class NlpModelDescriptor {

    private final String modelId;
    private final NlpCapability capability;
    private final String languageCode;
    private final String implementation;    // e.g. "opennlp"
    private final String version;           // model version, e.g. "1.5-model"
    private final String compatibleRuntime; // e.g. the opennlp-tools version the artifact was trained for
    private final String artifactPath;      // absolute local path to the deployed model file
    private final String sha256;

    public NlpModelDescriptor(String modelId, NlpCapability capability, String languageCode,
                              String implementation, String version, String compatibleRuntime,
                              String artifactPath, String sha256) {
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId must not be empty");
        }
        if (capability == null) {
            throw new IllegalArgumentException("capability is required");
        }
        if (artifactPath == null || artifactPath.trim().isEmpty()) {
            throw new IllegalArgumentException("artifactPath must not be empty");
        }
        this.modelId = modelId.trim();
        this.capability = capability;
        this.languageCode = languageCode == null ? "" : languageCode.trim().toLowerCase();
        this.implementation = implementation == null ? "" : implementation.trim();
        this.version = version == null ? "" : version.trim();
        this.compatibleRuntime = compatibleRuntime == null ? "" : compatibleRuntime.trim();
        this.artifactPath = artifactPath.trim();
        this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
    }

    public String getModelId() {
        return modelId;
    }

    public NlpCapability getCapability() {
        return capability;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public String getImplementation() {
        return implementation;
    }

    public String getVersion() {
        return version;
    }

    public String getCompatibleRuntime() {
        return compatibleRuntime;
    }

    public String getArtifactPath() {
        return artifactPath;
    }

    public String getSha256() {
        return sha256;
    }
}
