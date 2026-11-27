package com.aresstack.askai.agent.model.reranker;

import java.util.Collections;
import java.util.List;

/**
 * The neutral hand-off contract for a reranker endpoint the research agent may CALL — never START. It
 * describes an already-available endpoint: provider, base url, virtual model name, advertised
 * capabilities, score semantics, request timeout and the model-bound selection policy. It has no
 * process access, no paths and no knowledge of win-directml-java; the AskAI host produces it and the
 * research runtime consumes it over {@code POST <baseUrl>/api/rerank}.
 */
public final class RerankerEndpointDescriptor {

    public final RerankerProvider provider;
    public final String baseUrl;
    public final String modelName;
    public final List<RerankerCapability> capabilities;
    public final RerankerScoreSemantics scoreSemantics;
    public final long requestTimeoutMillis;
    public final RerankerSelectionConfiguration selectionConfiguration;

    public RerankerEndpointDescriptor(RerankerProvider provider, String baseUrl, String modelName,
                                      List<RerankerCapability> capabilities,
                                      RerankerScoreSemantics scoreSemantics, long requestTimeoutMillis,
                                      RerankerSelectionConfiguration selectionConfiguration) {
        this.provider = provider;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.modelName = modelName == null ? "" : modelName;
        this.capabilities = capabilities == null ? Collections.<RerankerCapability>emptyList()
                : Collections.unmodifiableList(capabilities);
        this.scoreSemantics = scoreSemantics;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.selectionConfiguration = selectionConfiguration;
    }

    public boolean hasCapability(RerankerCapability capability) {
        return capabilities.contains(capability);
    }
}
