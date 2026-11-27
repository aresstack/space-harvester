package com.aresstack.askai.browser.search;

/**
 * What the search pipeline records for the Technical-Details diagnostics. Bounded by construction:
 * no secrets, no unbounded DOM dumps — excerpts and artifact sizes are capped.
 */
public final class SearchDiagnosticsSettings {

    public final boolean enabled;
    public final boolean storeContainerDescriptors;
    public final boolean storeMechanicalScores;
    public final boolean storeVisualMetadata;
    public final boolean storePromptMetadata;
    /** Raw model responses are OFF by default — they may quote page content at length. */
    public final boolean storeRawModelResponses;
    public final boolean storeValidationFailures;
    public final boolean storeRetryHistory;
    public final boolean storeExtractedCandidates;
    public final boolean storeRerankerScores;
    public final int maximumTextExcerptCharacters;
    public final int maximumDiagnosticArtifactBytes;
    /** Replace URLs by their registrable domain in stored diagnostics. */
    public final boolean redactUrls;

    public SearchDiagnosticsSettings(boolean enabled, boolean storeContainerDescriptors,
                                     boolean storeMechanicalScores, boolean storeVisualMetadata,
                                     boolean storePromptMetadata, boolean storeRawModelResponses,
                                     boolean storeValidationFailures, boolean storeRetryHistory,
                                     boolean storeExtractedCandidates, boolean storeRerankerScores,
                                     int maximumTextExcerptCharacters, int maximumDiagnosticArtifactBytes,
                                     boolean redactUrls) {
        this.enabled = enabled;
        this.storeContainerDescriptors = storeContainerDescriptors;
        this.storeMechanicalScores = storeMechanicalScores;
        this.storeVisualMetadata = storeVisualMetadata;
        this.storePromptMetadata = storePromptMetadata;
        this.storeRawModelResponses = storeRawModelResponses;
        this.storeValidationFailures = storeValidationFailures;
        this.storeRetryHistory = storeRetryHistory;
        this.storeExtractedCandidates = storeExtractedCandidates;
        this.storeRerankerScores = storeRerankerScores;
        this.maximumTextExcerptCharacters = maximumTextExcerptCharacters;
        this.maximumDiagnosticArtifactBytes = maximumDiagnosticArtifactBytes;
        this.redactUrls = redactUrls;
    }
}
