package com.aresstack.askai.agent.model.reranker;

import java.util.List;

/**
 * The NEUTRAL host port listing the installed, usable rerank-capable local models by their virtual model
 * id (e.g. {@code local/cross-encoder/ms-marco-MiniLM-L-6-v2:latest}). The research runtime settings UI
 * uses it to offer an EXPLICIT reranker selection — only models with the {@code RERANK} capability appear,
 * and only this list feeds the one-time initial selection when exactly one reranker is installed.
 *
 * <p>The catalog never chooses a model itself: the persisted, explicit selection is the single truth and
 * is validated at session start by {@link RerankerConfigurationSnapshotProvider#prepareForSession}.
 */
public interface RerankerModelCatalog {

    /** The virtual model ids of all installed, usable rerank-capable local models (possibly empty). */
    List<String> listInstalledRerankModels();
}
