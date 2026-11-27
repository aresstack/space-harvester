package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedLinkDescriptor;

import java.util.Collections;
import java.util.List;

/**
 * One recognized RESULT BLOCK — the business unit of a SERP: primary title link, displayed domain,
 * explanatory snippet, optional sitelinks. Never "one anchor = one result".
 */
public final class DetectedResultBlock {

    public final String blockContainerId;
    /** 1-based position within the detected result list (the engine's original order). */
    public final int rank;
    public final RenderedLinkDescriptor primaryLink;
    public final double primaryLinkConfidence;
    public final String title;
    /** Explanatory non-link text of THIS block; may be empty when the settings allow it. */
    public final String snippet;
    public final String displayedDomain;
    /** Secondary links of the block (sitelinks) — metadata only, never own initial candidates. */
    public final List<RenderedLinkDescriptor> siteLinks;
    public final double structuralConfidence;

    public DetectedResultBlock(String blockContainerId, int rank,
                               RenderedLinkDescriptor primaryLink, double primaryLinkConfidence,
                               String title, String snippet, String displayedDomain,
                               List<RenderedLinkDescriptor> siteLinks,
                               double structuralConfidence) {
        this.blockContainerId = blockContainerId;
        this.rank = rank;
        this.primaryLink = primaryLink;
        this.primaryLinkConfidence = primaryLinkConfidence;
        this.title = title;
        this.snippet = snippet == null ? "" : snippet;
        this.displayedDomain = displayedDomain == null ? "" : displayedDomain;
        this.siteLinks = Collections.unmodifiableList(siteLinks);
        this.structuralConfidence = structuralConfidence;
    }
}
