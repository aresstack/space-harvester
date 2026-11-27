package com.aresstack.askai.browser.search.inference;

/**
 * The typed result of a {@link StructuredInferencePort} call: a status and, on
 * {@link StructuredInferenceStatus#SUCCESS}, the raw (still unvalidated) model text. {@code detail}
 * is a short, secret-free diagnostic for the non-success paths.
 */
public final class StructuredInferenceResult {

    public final StructuredInferenceStatus status;
    public final String rawText;
    public final String detail;

    public StructuredInferenceResult(StructuredInferenceStatus status, String rawText, String detail) {
        this.status = status == null ? StructuredInferenceStatus.PROVIDER_FAILURE : status;
        this.rawText = rawText == null ? "" : rawText;
        this.detail = detail == null ? "" : detail;
    }

    public static StructuredInferenceResult success(String rawText) {
        return new StructuredInferenceResult(StructuredInferenceStatus.SUCCESS, rawText, "");
    }

    public static StructuredInferenceResult of(StructuredInferenceStatus status, String detail) {
        return new StructuredInferenceResult(status, "", detail);
    }

    public boolean isSuccess() {
        return status == StructuredInferenceStatus.SUCCESS;
    }
}
