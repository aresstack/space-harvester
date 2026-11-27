package com.aresstack.askai.browser.search.inference;

/**
 * The single neutral seam between the model-free search analysis and whatever model runtime the
 * research process injects. The layout resolver builds the prompt, calls this, validates the answer
 * and drives repair retries; the port only turns a fully-described request into a typed result. No
 * concrete model library (Ollama, Solon ChatModel, …) is ever named on this contract — the browser
 * sidecar must never see an implementation of it.
 */
public interface StructuredInferencePort {

    StructuredInferenceResult execute(StructuredInferenceRequest request);
}
