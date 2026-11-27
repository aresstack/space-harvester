package com.aresstack.askai.agent.model.reranker;

import java.io.File;

/**
 * A published, on-disk reranker start snapshot for one session: the absolute file the agent receives
 * through {@code ASKAI_RERANKER_CONFIG}, together with the validated document that was written (so the
 * host can log the served model without re-reading the file). The file is immutable for the session.
 */
public final class RerankerConfigurationSnapshot {

    private final File snapshotFile;
    private final RerankerConfigurationDocument document;

    public RerankerConfigurationSnapshot(File snapshotFile, RerankerConfigurationDocument document) {
        this.snapshotFile = snapshotFile;
        this.document = document;
    }

    public File getSnapshotFile() {
        return snapshotFile;
    }

    public String getAbsolutePath() {
        return snapshotFile.getAbsolutePath();
    }

    public RerankerConfigurationDocument getDocument() {
        return document;
    }

    /** The served model name (for diagnostics/logging; never a secret). */
    public String getModelName() {
        return document.descriptor.modelName;
    }
}
