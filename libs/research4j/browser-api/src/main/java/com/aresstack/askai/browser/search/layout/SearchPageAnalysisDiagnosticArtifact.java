package com.aresstack.askai.browser.search.layout;

import java.util.Collections;
import java.util.List;

/**
 * The typed, BOUNDED diagnostic projection of one analysis (A4e): the mechanical selection and score
 * breakdowns, the rejected containers, every AI attempt with its validation failures and repair
 * retries, the profile hit/rejection and the final outcome. It is the data the UI renders from —
 * tests must never parse the short {@code ATTEMPT:} line as a data contract. Raw model responses ride
 * inside the attempts only when {@code storeRawModelResponses} is set; size is capped and secrets are
 * never present because every field is a neutral projection.
 */
public final class SearchPageAnalysisDiagnosticArtifact {

    public final String analysisId;
    public final String snapshotId;
    public final EngineFamily engineFamily;

    public final MechanicalConfidenceOutcome mechanicalOutcome;
    public final double mechanicalConfidence;
    public final List<String> mechanicallyPreferredContainerIds;
    public final List<SearchPageContainerCandidate> mechanicalCandidates;
    public final List<String> rejectedContainers;

    public final List<SearchPageAnalysisAttempt> aiAttempts;
    public final List<String> validationFailures;
    public final int repairRetries;
    public final boolean rawModelResponsesIncluded;

    public final String profileOutcome;
    public final String finalOutcome;
    /** True when a bound (candidate/attempt) was truncated to respect the artifact byte budget. */
    public final boolean truncated;

    public SearchPageAnalysisDiagnosticArtifact(String analysisId, String snapshotId,
                                                EngineFamily engineFamily,
                                                MechanicalConfidenceOutcome mechanicalOutcome,
                                                double mechanicalConfidence,
                                                List<String> mechanicallyPreferredContainerIds,
                                                List<SearchPageContainerCandidate> mechanicalCandidates,
                                                List<String> rejectedContainers,
                                                List<SearchPageAnalysisAttempt> aiAttempts,
                                                List<String> validationFailures, int repairRetries,
                                                boolean rawModelResponsesIncluded,
                                                String profileOutcome, String finalOutcome,
                                                boolean truncated) {
        this.analysisId = safe(analysisId);
        this.snapshotId = safe(snapshotId);
        this.engineFamily = engineFamily == null ? EngineFamily.UNKNOWN : engineFamily;
        this.mechanicalOutcome = mechanicalOutcome == null
                ? MechanicalConfidenceOutcome.LOW_CONFIDENCE : mechanicalOutcome;
        this.mechanicalConfidence = mechanicalConfidence;
        this.mechanicallyPreferredContainerIds = unmodifiable(mechanicallyPreferredContainerIds);
        this.mechanicalCandidates = mechanicalCandidates == null
                ? Collections.<SearchPageContainerCandidate>emptyList()
                : Collections.unmodifiableList(mechanicalCandidates);
        this.rejectedContainers = unmodifiable(rejectedContainers);
        this.aiAttempts = aiAttempts == null
                ? Collections.<SearchPageAnalysisAttempt>emptyList()
                : Collections.unmodifiableList(aiAttempts);
        this.validationFailures = unmodifiable(validationFailures);
        this.repairRetries = repairRetries;
        this.rawModelResponsesIncluded = rawModelResponsesIncluded;
        this.profileOutcome = safe(profileOutcome);
        this.finalOutcome = safe(finalOutcome);
        this.truncated = truncated;
    }

    private static List<String> unmodifiable(List<String> value) {
        return value == null ? Collections.<String>emptyList() : Collections.unmodifiableList(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
