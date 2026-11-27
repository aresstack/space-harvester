package com.aresstack.askai.browser.search;

/**
 * AI layout resolver contract (future slice — A2 defines the contract, no model call is implemented).
 * The model only ever CHOOSES among mechanically found container ids; unknown ids are a validation
 * failure handled by the {@link AiRetryPolicy}, never silently accepted (hard invariant).
 */
public final class AiLayoutResolverSettings {

    public final boolean enabled;
    /** The model profile to call (host-side model registry id); empty = feature unusable, validated. */
    public final String modelProfileId;
    public final ReasoningEffort reasoningEffort;
    public final double temperature;
    public final int maximumOutputTokens;
    /** Productive default text, not a placeholder. Template variables are validated to be present. */
    public final String systemPromptTemplate;
    public final String userPromptTemplate;
    public final AiRetryPolicy retryPolicy;

    public AiLayoutResolverSettings(boolean enabled, String modelProfileId,
                                    ReasoningEffort reasoningEffort, double temperature,
                                    int maximumOutputTokens, String systemPromptTemplate,
                                    String userPromptTemplate, AiRetryPolicy retryPolicy) {
        this.enabled = enabled;
        this.modelProfileId = modelProfileId;
        this.reasoningEffort = reasoningEffort;
        this.temperature = temperature;
        this.maximumOutputTokens = maximumOutputTokens;
        this.systemPromptTemplate = systemPromptTemplate;
        this.userPromptTemplate = userPromptTemplate;
        this.retryPolicy = retryPolicy;
    }
}
