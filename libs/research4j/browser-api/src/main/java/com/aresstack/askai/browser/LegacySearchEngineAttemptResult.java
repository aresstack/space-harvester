package com.aresstack.askai.browser;

/**
 * One engine attempt of a browser-based search: which engine (host), which typed
 * {@link LegacySearchAttemptOutcome}, and a short diagnostic code. Carried on the
 * {@link WebSearchResult} so a search stays explainable per engine.
 */
public final class LegacySearchEngineAttemptResult {

    private final String searchEngineHost;
    private final LegacySearchAttemptOutcome outcome;
    private final String diagnosticCode;

    public LegacySearchEngineAttemptResult(String searchEngineHost, LegacySearchAttemptOutcome outcome,
                                           String diagnosticCode) {
        this.searchEngineHost = searchEngineHost == null ? "" : searchEngineHost;
        this.outcome = outcome == null ? LegacySearchAttemptOutcome.EXTRACTION_FAILED : outcome;
        this.diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
    }

    public String getSearchEngineHost() {
        return searchEngineHost;
    }

    public LegacySearchAttemptOutcome getOutcome() {
        return outcome;
    }

    public String getDiagnosticCode() {
        return diagnosticCode;
    }

    @Override
    public String toString() {
        return searchEngineHost + " " + outcome
                + (diagnosticCode.isEmpty() ? "" : " (" + diagnosticCode + ")");
    }
}
