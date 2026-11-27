package com.aresstack.askai.browser.search.layout;

import com.aresstack.askai.browser.search.AiLayoutResolverSettings;
import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;

/**
 * Everything the AI layout resolver needs for one mechanically ununderstood snapshot: the bounded
 * {@link SearchPageAnalysisArtifact}, the AI settings (prompt templates, model profile, retry
 * policy), the diagnostics bounds and a cancellation signal. It carries no document and no model
 * library type — the resolver reasons only over the artifact's snapshot-local descriptors.
 */
public final class SearchPageLayoutResolutionRequest {

    public final SearchPageAnalysisArtifact artifact;
    public final AiLayoutResolverSettings aiSettings;
    public final SearchDiagnosticsSettings diagnosticsSettings;
    public final CancellationSignal cancellationSignal;

    public SearchPageLayoutResolutionRequest(SearchPageAnalysisArtifact artifact,
                                             AiLayoutResolverSettings aiSettings,
                                             SearchDiagnosticsSettings diagnosticsSettings,
                                             CancellationSignal cancellationSignal) {
        this.artifact = artifact;
        this.aiSettings = aiSettings;
        this.diagnosticsSettings = diagnosticsSettings;
        this.cancellationSignal =
                cancellationSignal == null ? CancellationSignal.NONE : cancellationSignal;
    }
}
