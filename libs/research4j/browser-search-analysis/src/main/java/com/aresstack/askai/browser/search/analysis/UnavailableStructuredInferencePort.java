package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;

/**
 * The safe production default when no model adapter has been injected: every call is typed
 * {@link StructuredInferenceStatus#UNAVAILABLE}. Wiring the AI layout resolver with this keeps the
 * whole path testable and honest — a low-confidence page falls back through the existing engine
 * policy instead of fabricating a result. It is also what the model-free browser sidecar would use if
 * it ever constructed a resolver.
 */
public final class UnavailableStructuredInferencePort implements StructuredInferencePort {

    public StructuredInferenceResult execute(StructuredInferenceRequest request) {
        return StructuredInferenceResult.of(StructuredInferenceStatus.UNAVAILABLE,
                "no model adapter is wired");
    }
}
