package com.aresstack.askai.browser.search.inference;

import com.aresstack.askai.browser.search.ReasoningEffort;

/**
 * One structured-inference call, fully described and model-library agnostic. {@link #modelProfileId}
 * is a LOGICAL model reference resolved by whatever adapter the research runtime injects — never a
 * hardcoded URL, never a concrete library type. {@link #attemptNumber} is 1-based so an adapter can
 * log repair attempts; the layout resolver owns the retry policy, not the port.
 */
public final class StructuredInferenceRequest {

    public final String modelProfileId;
    public final String systemPrompt;
    public final String userPrompt;
    public final int maximumOutputTokens;
    public final double temperature;
    public final ReasoningEffort reasoningEffort;
    public final int attemptNumber;
    public final CancellationSignal cancellationSignal;

    public StructuredInferenceRequest(String modelProfileId, String systemPrompt, String userPrompt,
                                      int maximumOutputTokens, double temperature,
                                      ReasoningEffort reasoningEffort, int attemptNumber,
                                      CancellationSignal cancellationSignal) {
        this.modelProfileId = modelProfileId == null ? "" : modelProfileId;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.userPrompt = userPrompt == null ? "" : userPrompt;
        this.maximumOutputTokens = maximumOutputTokens;
        this.temperature = temperature;
        this.reasoningEffort = reasoningEffort == null ? ReasoningEffort.DEFAULT : reasoningEffort;
        this.attemptNumber = attemptNumber;
        this.cancellationSignal =
                cancellationSignal == null ? CancellationSignal.NONE : cancellationSignal;
    }
}
