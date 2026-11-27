package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisAttempt;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisDiagnosticArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects the mechanical artifact, the AI attempt history and the profile outcome into the typed,
 * BOUNDED {@link SearchPageAnalysisDiagnosticArtifact} the UI renders from (A4e). It applies the
 * diagnostics byte budget by capping the candidate list, only surfaces raw model responses when
 * {@code storeRawModelResponses} is set, and redacts urls from any surfaced raw text when
 * {@code redactUrls} is set. Nothing here parses the short {@code ATTEMPT:} line — that stays a pure
 * short view; internal decisions travel typed.
 */
public final class SearchPageAnalysisDiagnosticArtifactBuilder {

    /** Conservative per-candidate byte estimate used to derive the candidate cap from the budget. */
    private static final int APPROX_BYTES_PER_CANDIDATE = 512;

    private final SearchDiagnosticsSettings diagnostics;

    public SearchPageAnalysisDiagnosticArtifactBuilder(SearchDiagnosticsSettings diagnostics) {
        this.diagnostics = diagnostics;
    }

    public SearchPageAnalysisDiagnosticArtifact build(SearchPageAnalysisArtifact artifact,
                                                      SearchPageLayoutResolverResult resolverResult,
                                                      String profileOutcome, String finalOutcome) {
        int candidateCap = Math.max(1,
                diagnostics.maximumDiagnosticArtifactBytes / APPROX_BYTES_PER_CANDIDATE);
        boolean truncated = artifact.containerCandidates.size() > candidateCap;
        List<SearchPageContainerCandidate> candidates =
                new ArrayList<SearchPageContainerCandidate>(truncated
                        ? artifact.containerCandidates.subList(0, candidateCap)
                        : artifact.containerCandidates);

        List<SearchPageAnalysisAttempt> attempts = new ArrayList<SearchPageAnalysisAttempt>();
        List<String> validationFailures = new ArrayList<String>();
        boolean rawIncluded = false;
        if (resolverResult != null) {
            for (SearchPageAnalysisAttempt attempt : resolverResult.attempts) {
                String raw = surfaceRaw(attempt.rawResponse);
                if (!raw.isEmpty()) {
                    rawIncluded = true;
                }
                attempts.add(new SearchPageAnalysisAttempt(attempt.attemptNumber,
                        attempt.inferenceStatus, attempt.parsed, attempt.accepted,
                        attempt.violations, raw));
                validationFailures.addAll(attempt.violations);
            }
        }
        int repairRetries = Math.max(0, attempts.size() - 1);

        return new SearchPageAnalysisDiagnosticArtifact(artifact.analysisId, artifact.snapshotId,
                artifact.engineFamily, artifact.mechanicalOutcome, artifact.mechanicalConfidence,
                artifact.mechanicallyPreferredContainerIds, candidates,
                artifact.mechanicalRejectionReasons, attempts, validationFailures, repairRetries,
                rawIncluded, profileOutcome, finalOutcome, truncated);
    }

    private String surfaceRaw(String rawResponse) {
        if (!diagnostics.storeRawModelResponses || rawResponse == null || rawResponse.isEmpty()) {
            return "";
        }
        String capped = rawResponse.length() <= diagnostics.maximumTextExcerptCharacters
                ? rawResponse
                : rawResponse.substring(0, diagnostics.maximumTextExcerptCharacters);
        return diagnostics.redactUrls ? redactUrls(capped) : capped;
    }

    /** Replace absolute http(s) urls by a marker so surfaced raw text never leaks full urls. */
    private static String redactUrls(String text) {
        return text.replaceAll("https?://[^\\s\"']+", "[redacted-url]");
    }
}
