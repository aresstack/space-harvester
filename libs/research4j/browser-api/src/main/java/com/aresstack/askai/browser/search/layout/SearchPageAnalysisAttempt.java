package com.aresstack.askai.browser.search.layout;

import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;

import java.util.Collections;
import java.util.List;

/**
 * One recorded AI attempt: the 1-based attempt number, the port status, whether the response parsed
 * and was accepted, the concrete validation-violation summaries (empty until A4c fills them) and an
 * OPTIONAL raw response. The raw response is only ever populated when
 * {@code SearchDiagnosticsSettings.storeRawModelResponses} is set — otherwise it stays empty so page
 * content is never retained. Failed attempts are preserved so a later success still carries its
 * history.
 */
public final class SearchPageAnalysisAttempt {

    public final int attemptNumber;
    public final StructuredInferenceStatus inferenceStatus;
    public final boolean parsed;
    public final boolean accepted;
    public final List<String> violations;
    public final String rawResponse;

    public SearchPageAnalysisAttempt(int attemptNumber, StructuredInferenceStatus inferenceStatus,
                                     boolean parsed, boolean accepted, List<String> violations,
                                     String rawResponse) {
        this.attemptNumber = attemptNumber;
        this.inferenceStatus = inferenceStatus == null
                ? StructuredInferenceStatus.PROVIDER_FAILURE : inferenceStatus;
        this.parsed = parsed;
        this.accepted = accepted;
        this.violations = violations == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(violations);
        this.rawResponse = rawResponse == null ? "" : rawResponse;
    }
}
