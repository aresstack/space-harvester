package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.search.SearchPageAnalysisSettings;
import com.aresstack.askai.browser.search.SearchResultExtractionSettings;

import java.util.List;

/**
 * Picks the PRIMARY title link of one result block. Positive: an external (or resolved-external)
 * target, a link inside a heading, meaningful link text, an early position, explanatory text next
 * to it. Negative: engine-internal targets and too-short text. A link whose wrapper could not be
 * resolved ({@link LinkRedirectResolution#UNRESOLVED}, e.g. Google {@code /goto}) can still be the
 * primary link (askai #41, lazy resolution): the hit stays an unverified candidate that the browser
 * opens through its raw href. A resolved link of the same block is preferred. All weights come from
 * the settings.
 */
public final class PrimaryResultLinkResolver {

    /** The chosen link plus its normalized confidence. */
    public static final class Primary {
        public final RenderedLinkDescriptor link;
        public final double confidence;

        Primary(RenderedLinkDescriptor link, double confidence) {
            this.link = link;
            this.confidence = confidence;
        }
    }

    private final SearchPageAnalysisSettings analysis;
    private final SearchResultExtractionSettings extraction;

    public PrimaryResultLinkResolver(SearchPageAnalysisSettings analysis,
                                     SearchResultExtractionSettings extraction) {
        this.analysis = analysis;
        this.extraction = extraction;
    }

    /** @param blockLinks the block's links in DOCUMENT order. @return null when none qualifies. */
    public Primary resolve(List<RenderedLinkDescriptor> blockLinks) {
        RenderedLinkDescriptor best = null;
        double bestScore = 0;
        for (int position = 0; position < blockLinks.size(); position++) {
            RenderedLinkDescriptor link = blockLinks.get(position);
            boolean unresolved = link.redirectResolutionStatus == LinkRedirectResolution.UNRESOLVED;
            if (!link.visible || (unresolved ? link.rawHref.isEmpty() : link.resolvedTargetUrl.isEmpty())) {
                continue; // nothing to navigate — never a primary candidate
            }
            if (link.visibleText.trim().length() < extraction.minimumTitleCharacters) {
                continue; // too short to be a title
            }
            double score = 0;
            if (link.domainClassification == DomainClassification.EXTERNAL_DOMAIN) {
                score += analysis.externalLinkWeight;
            } else {
                // Engine-internal targets (verticals, settings, refinements) are penalized but a
                // subdomain result on the engine's own family (finance.yahoo.com) stays possible.
                score -= penaltyFor(link.domainClassification);
            }
            if (link.insideHeading) {
                score += analysis.headingLinkWeight;
            }
            // The minimum-title gate above already vouched for meaningful text.
            score += analysis.titleLinkWeight;
            if (position == 0) {
                score += analysis.titleLinkWeight / 2; // prominent first position
            }
            if (unresolved) {
                score -= analysis.titleLinkWeight / 4; // a verified link of the same block wins
            }
            if (!link.surroundingTextExcerpt.trim().isEmpty()) {
                score += analysis.snippetPresenceWeight / 2; // explanatory text sits next to it
            }
            if (score > bestScore) {
                bestScore = score;
                best = link;
            }
        }
        if (best == null) {
            return null;
        }
        // Normalized WITHOUT the external bonus: an external target pushes towards 1, an
        // engine-internal penalty pulls down — but a structurally solid subdomain result
        // (finance.yahoo.com on a Yahoo engine) is not wholesale rejected as a menu.
        double maximum = analysis.headingLinkWeight + analysis.titleLinkWeight
                + analysis.titleLinkWeight / 2 + analysis.snippetPresenceWeight / 2;
        double confidence = maximum <= 0 ? 0 : Math.max(0, Math.min(1, bestScore / maximum));
        return confidence >= extraction.minimumPrimaryLinkConfidence
                ? new Primary(best, confidence) : null;
    }

    private double penaltyFor(DomainClassification classification) {
        switch (classification) {
            case SAME_HOST:
                return analysis.sameHostPenalty;
            case SUBDOMAIN:
                return analysis.subdomainPenalty;
            case SAME_REGISTRABLE_DOMAIN:
                return analysis.sameRegistrableDomainPenalty;
            default:
                return analysis.unknownDomainPenalty;
        }
    }
}
