package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.List;

/**
 * One typed organic search candidate extracted from a recognized result block. The
 * {@link #resolvedTargetUrl} is what will be NAVIGATED; {@link #rawSearchHref} is diagnostic
 * provenance only (redirect wrappers are never opened). Container references are valid only
 * together with {@link #snapshotId} — against another snapshot they are stale.
 */
public final class SearchResultCandidate {

    public final String candidateId;
    public final String snapshotId;
    public final String resolvedTargetUrl;
    public final String rawSearchHref;
    public final String title;
    public final String snippet;
    public final String displayedDomain;
    /** 1-based rank in the engine's original order. */
    public final int originalRank;
    public final String resultContainerId;
    public final String resultBlockContainerId;
    public final double structuralConfidence;
    public final double primaryLinkConfidence;
    /** Stored as metadata; in A3 sitelinks are never emitted as own initial candidates. */
    public final List<SearchResultSiteLink> siteLinks;
    /**
     * WHICH engine this hit came from. When several engines are asked, the union has to stay
     * accountable: "who said this" is part of the finding, not a detail of how it was fetched. Empty
     * when the source did not name an engine (API providers, synthetic candidates).
     */
    public final String engineHost;
    /**
     * Whether {@link #resolvedTargetUrl} is a verified target (askai #41). For {@link UrlResolutionStatus#UNRESOLVED}
     * the target is empty and the hit is navigated through {@link #rawSearchHref}; see {@link #navigationUrl()}.
     */
    public final UrlResolutionStatus urlResolution;
    /** The provider's URL before resolution (e.g. a Google {@code /goto} link); empty when there was none. */
    public final String originalUrl;

    public SearchResultCandidate(String candidateId, String snapshotId, String resolvedTargetUrl,
                                 String rawSearchHref, String title, String snippet,
                                 String displayedDomain, int originalRank,
                                 String resultContainerId, String resultBlockContainerId,
                                 double structuralConfidence, double primaryLinkConfidence,
                                 List<SearchResultSiteLink> siteLinks) {
        this(candidateId, snapshotId, resolvedTargetUrl, rawSearchHref, title, snippet,
                displayedDomain, originalRank, resultContainerId, resultBlockContainerId,
                structuralConfidence, primaryLinkConfidence, siteLinks, "");
    }

    public SearchResultCandidate(String candidateId, String snapshotId, String resolvedTargetUrl,
                                 String rawSearchHref, String title, String snippet,
                                 String displayedDomain, int originalRank,
                                 String resultContainerId, String resultBlockContainerId,
                                 double structuralConfidence, double primaryLinkConfidence,
                                 List<SearchResultSiteLink> siteLinks, String engineHost) {
        this(candidateId, snapshotId, resolvedTargetUrl, rawSearchHref, title, snippet, displayedDomain,
                originalRank, resultContainerId, resultBlockContainerId, structuralConfidence,
                primaryLinkConfidence, siteLinks, engineHost, UrlResolutionStatus.DIRECT, "");
    }

    public SearchResultCandidate(String candidateId, String snapshotId, String resolvedTargetUrl,
                                 String rawSearchHref, String title, String snippet,
                                 String displayedDomain, int originalRank,
                                 String resultContainerId, String resultBlockContainerId,
                                 double structuralConfidence, double primaryLinkConfidence,
                                 List<SearchResultSiteLink> siteLinks, String engineHost,
                                 UrlResolutionStatus urlResolution, String originalUrl) {
        this.engineHost = engineHost == null ? "" : engineHost;
        this.urlResolution = urlResolution == null ? UrlResolutionStatus.DIRECT : urlResolution;
        this.originalUrl = originalUrl == null ? "" : originalUrl;
        this.candidateId = candidateId;
        this.snapshotId = snapshotId;
        this.resolvedTargetUrl = resolvedTargetUrl == null ? "" : resolvedTargetUrl;
        this.rawSearchHref = rawSearchHref == null ? "" : rawSearchHref;
        this.title = title == null ? "" : title;
        this.snippet = snippet == null ? "" : snippet;
        this.displayedDomain = displayedDomain == null ? "" : displayedDomain;
        this.originalRank = originalRank;
        this.resultContainerId = resultContainerId == null ? "" : resultContainerId;
        this.resultBlockContainerId = resultBlockContainerId == null ? "" : resultBlockContainerId;
        this.structuralConfidence = structuralConfidence;
        this.primaryLinkConfidence = primaryLinkConfidence;
        this.siteLinks = Collections.unmodifiableList(siteLinks);
    }

    /** The same hit, attributed to the engine that produced it. */
    public SearchResultCandidate attributedTo(String engine) {
        return new SearchResultCandidate(candidateId, snapshotId, resolvedTargetUrl, rawSearchHref,
                title, snippet, displayedDomain, originalRank, resultContainerId,
                resultBlockContainerId, structuralConfidence, primaryLinkConfidence, siteLinks,
                engine, urlResolution, originalUrl);
    }

    /** {@code true} when {@link #resolvedTargetUrl} is a verified target address (DIRECT or RESOLVED). */
    public boolean hasVerifiedTarget() {
        return urlResolution.isVerifiedTarget() && !resolvedTargetUrl.isEmpty();
    }

    /**
     * What the browser opens for this hit: the verified target, or for an UNRESOLVED hit its provider URL (the
     * browser follows the redirect; only the page it lands on can become a source). Empty when there is none.
     */
    public String navigationUrl() {
        return hasVerifiedTarget() ? resolvedTargetUrl
                : urlResolution == UrlResolutionStatus.UNRESOLVED ? rawSearchHref : resolvedTargetUrl;
    }
}
