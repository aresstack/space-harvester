package com.aresstack.askai.browser.search;

/** Limits and thresholds for turning analyzed result containers into typed search result candidates. */
public final class SearchResultExtractionSettings {

    public final int minimumTitleCharacters;
    public final int minimumSnippetCharacters;
    public final int maximumSnippetCharacters;
    public final int maximumExtractedCandidates;
    public final int maximumSiteLinksPerResult;
    /** Candidates whose primary-link confidence (0..1) is below this are dropped. */
    public final double minimumPrimaryLinkConfidence;
    /** Candidates below this structural confidence (0..1) never reach the reranker. */
    public final double minimumStructuralConfidenceForReranking;

    public SearchResultExtractionSettings(int minimumTitleCharacters, int minimumSnippetCharacters,
                                          int maximumSnippetCharacters, int maximumExtractedCandidates,
                                          int maximumSiteLinksPerResult,
                                          double minimumPrimaryLinkConfidence,
                                          double minimumStructuralConfidenceForReranking) {
        this.minimumTitleCharacters = minimumTitleCharacters;
        this.minimumSnippetCharacters = minimumSnippetCharacters;
        this.maximumSnippetCharacters = maximumSnippetCharacters;
        this.maximumExtractedCandidates = maximumExtractedCandidates;
        this.maximumSiteLinksPerResult = maximumSiteLinksPerResult;
        this.minimumPrimaryLinkConfidence = minimumPrimaryLinkConfidence;
        this.minimumStructuralConfidenceForReranking = minimumStructuralConfidenceForReranking;
    }
}
