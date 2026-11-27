package com.aresstack.askai.browser.render;

import com.aresstack.askai.browser.domain.DomainClassification;

/**
 * One captured link. The raw search-redirect wrapper URL is DIAGNOSTIC provenance only: once a
 * wrapper (Bing /ck/, Google /url, DuckDuckGo /l/) was statically resolved, the direct
 * {@link #resolvedTargetUrl} is the later navigation candidate — tracking wrappers are not
 * navigated and the research never detours back through the engine's domain per hit.
 */
public final class RenderedLinkDescriptor {

    public final String linkId;
    /** The container this link belongs to (innermost captured container). */
    public final String containerId;
    /** Origin/diagnostics only — never the navigation target once resolved. */
    public final String rawHref;
    /** The URL that will later be OPENED (raw href when NOT_A_REDIRECT; empty when UNRESOLVED). */
    public final String resolvedTargetUrl;
    public final LinkRedirectResolution redirectResolutionStatus;
    public final String visibleText;
    /** Non-link text near this link inside its block (excerpt). */
    public final String surroundingTextExcerpt;
    public final String nearestHeadingText;
    /** The domain text the page DISPLAYS next to the link (cite/breadcrumb), or empty. */
    public final String displayedDomainText;
    /** Relation of the RESOLVED target to the page host. */
    public final DomainClassification domainClassification;
    public final boolean visible;
    public final RenderedBox boundingBox;
    /** True when the link sits inside a heading element (h1..h6). */
    public final boolean insideHeading;

    public RenderedLinkDescriptor(String linkId, String containerId, String rawHref,
                                  String resolvedTargetUrl,
                                  LinkRedirectResolution redirectResolutionStatus,
                                  String visibleText, String surroundingTextExcerpt,
                                  String nearestHeadingText, String displayedDomainText,
                                  DomainClassification domainClassification, boolean visible,
                                  RenderedBox boundingBox, boolean insideHeading) {
        this.linkId = linkId;
        this.containerId = containerId;
        this.rawHref = rawHref == null ? "" : rawHref;
        this.resolvedTargetUrl = resolvedTargetUrl == null ? "" : resolvedTargetUrl;
        this.redirectResolutionStatus = redirectResolutionStatus;
        this.visibleText = visibleText == null ? "" : visibleText;
        this.surroundingTextExcerpt = surroundingTextExcerpt == null ? "" : surroundingTextExcerpt;
        this.nearestHeadingText = nearestHeadingText == null ? "" : nearestHeadingText;
        this.displayedDomainText = displayedDomainText == null ? "" : displayedDomainText;
        this.domainClassification = domainClassification;
        this.visible = visible;
        this.boundingBox = boundingBox;
        this.insideHeading = insideHeading;
    }
}
