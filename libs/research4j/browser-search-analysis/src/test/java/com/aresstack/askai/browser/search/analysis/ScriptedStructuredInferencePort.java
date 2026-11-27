package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic {@link StructuredInferencePort} test double: it replays a scripted sequence of
 * results (one per attempt) and records every request it received, so tests can assert both the
 * resolver's behaviour and the EXACT number of model calls (e.g. zero on a high-confidence page).
 */
final class ScriptedStructuredInferencePort implements StructuredInferencePort {

    private final List<StructuredInferenceResult> scripted = new ArrayList<StructuredInferenceResult>();
    final List<StructuredInferenceRequest> requests = new ArrayList<StructuredInferenceRequest>();
    private int index;

    static ScriptedStructuredInferencePort of(StructuredInferenceResult... results) {
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort();
        for (StructuredInferenceResult result : results) {
            port.scripted.add(result);
        }
        return port;
    }

    ScriptedStructuredInferencePort thenSuccess(String rawText) {
        scripted.add(StructuredInferenceResult.success(rawText));
        return this;
    }

    ScriptedStructuredInferencePort thenStatus(StructuredInferenceStatus status, String detail) {
        scripted.add(StructuredInferenceResult.of(status, detail));
        return this;
    }

    int callCount() {
        return requests.size();
    }

    public StructuredInferenceResult execute(StructuredInferenceRequest request) {
        requests.add(request);
        if (index >= scripted.size()) {
            // Nothing left scripted — behave as an exhausted provider rather than throwing.
            return StructuredInferenceResult.of(StructuredInferenceStatus.PROVIDER_FAILURE,
                    "no scripted response for call " + (index + 1));
        }
        return scripted.get(index++);
    }
}
