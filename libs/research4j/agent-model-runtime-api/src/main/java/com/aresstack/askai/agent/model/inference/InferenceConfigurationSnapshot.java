package com.aresstack.askai.agent.model.inference;

import java.io.File;

/** The published on-disk handle: the written {@code inference-config.json} file and its decoded document. */
public final class InferenceConfigurationSnapshot {

    private final File snapshotFile;
    private final InferenceConfigurationDocument document;

    public InferenceConfigurationSnapshot(File snapshotFile, InferenceConfigurationDocument document) {
        this.snapshotFile = snapshotFile;
        this.document = document;
    }

    public File getSnapshotFile() {
        return snapshotFile;
    }

    public String getAbsolutePath() {
        return snapshotFile.getAbsolutePath();
    }

    public InferenceConfigurationDocument getDocument() {
        return document;
    }

    public String getModel() {
        return document.getModel();
    }
}
