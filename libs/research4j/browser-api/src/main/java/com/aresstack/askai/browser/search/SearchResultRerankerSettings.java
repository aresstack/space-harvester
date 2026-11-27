package com.aresstack.askai.browser.search;

/** Result reranker contract (future slice — A2 defines the contract, no reranker is implemented). */
public final class SearchResultRerankerSettings {

    public final boolean enabled;
    public final RerankerImplementationType implementationType;
    /** Only meaningful for model-backed implementations; empty otherwise. */
    public final String modelProfileId;
    public final ReasoningEffort reasoningEffort;
    public final int maximumCandidates;
    public final int maximumSelectedResults;
    public final double structuralScoreWeight;
    public final double semanticScoreWeight;
    public final double originalRankWeight;
    public final String promptTemplate;
    public final AiRetryPolicy retryPolicy;

    public SearchResultRerankerSettings(boolean enabled, RerankerImplementationType implementationType,
                                        String modelProfileId, ReasoningEffort reasoningEffort,
                                        int maximumCandidates, int maximumSelectedResults,
                                        double structuralScoreWeight, double semanticScoreWeight,
                                        double originalRankWeight, String promptTemplate,
                                        AiRetryPolicy retryPolicy) {
        this.enabled = enabled;
        this.implementationType = implementationType;
        this.modelProfileId = modelProfileId;
        this.reasoningEffort = reasoningEffort;
        this.maximumCandidates = maximumCandidates;
        this.maximumSelectedResults = maximumSelectedResults;
        this.structuralScoreWeight = structuralScoreWeight;
        this.semanticScoreWeight = semanticScoreWeight;
        this.originalRankWeight = originalRankWeight;
        this.promptTemplate = promptTemplate;
        this.retryPolicy = retryPolicy;
    }
}
