package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionRequest;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolver;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The full mechanical extraction pipeline (A3d) plus the A4d AI repair path, with HONEST outcome
 * semantics:
 * <ul>
 * <li>{@code ORGANIC_RESULTS} — validated result blocks were extracted,</li>
 * <li>{@code NO_ORGANIC_RESULTS} — a valid SERP or an EXPLICIT no-results indication with
 *     genuinely zero hits,</li>
 * <li>{@code EXTRACTION_FAILED} — no plausible result region, contradictory structure, low
 *     confidence with no usable AI resolution, or a capture failure. An ununderstood layout is NEVER
 *     reported as an empty engine.</li>
 * </ul>
 *
 * <p>HIGH_CONFIDENCE never calls the AI. On LOW_CONFIDENCE, when — and only when — a
 * {@link SearchPageLayoutResolver} was injected, the ununderstood layout is offered to it as a
 * bounded artifact; a VALIDATED decision then feeds the EXISTING result-block extraction. Absent a
 * resolver (the model-free sidecar) or on a disabled/unavailable resolver, LOW_CONFIDENCE stays
 * EXTRACTION_FAILED and the engine-fallback policy moves on. The AI only ever replaces the uncertain
 * LAYOUT resolution — primary-link selection, snippet extraction, sitelinks and dedup remain the
 * single A3 implementation.</p>
 */
public final class LegacySearchResultExtractor {

    private final LegacyBrowserSearchSettings settings;
    private final SearchPageMechanicalAnalyzer analyzer;
    private final SearchResultBlockDetector blockDetector;
    private final SearchPageAnalysisArtifactBuilder artifactBuilder;
    private final SearchPageLayoutResolver aiResolver;
    private final CancellationSignal cancellationSignal;

    public LegacySearchResultExtractor(LegacyBrowserSearchSettings settings) {
        this(settings, null, CancellationSignal.NONE);
    }

    /**
     * Wire an AI layout resolver for the LOW_CONFIDENCE path. The sidecar keeps using the single-arg
     * constructor and therefore stays model-free.
     */
    public LegacySearchResultExtractor(LegacyBrowserSearchSettings settings,
                                       SearchPageLayoutResolver aiResolver,
                                       CancellationSignal cancellationSignal) {
        this.settings = settings;
        this.analyzer = new SearchPageMechanicalAnalyzer(settings);
        this.blockDetector = new SearchResultBlockDetector(settings);
        this.artifactBuilder = new SearchPageAnalysisArtifactBuilder(settings);
        this.aiResolver = aiResolver;
        this.cancellationSignal =
                cancellationSignal == null ? CancellationSignal.NONE : cancellationSignal;
    }

    public SearchResultExtractionResult extract(RenderedPageDocument document) {
        return extract(document, "");
    }

    public SearchResultExtractionResult extract(RenderedPageDocument document, String searchQuery) {
        return withLinkHarvest(document, extractStructured(document, searchQuery));
    }

    private SearchResultExtractionResult extractStructured(RenderedPageDocument document,
                                                           String searchQuery) {
        List<String> diagnostics = new ArrayList<String>(document.captureWarnings);
        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        if (resolution.lowConfidence) {
            if (showsExplicitNoResults(document)) {
                diagnostics.add("explicit no-results indication on a page without a result region");
                return noOrganicResults(document, resolution.confidence, diagnostics);
            }
            if (aiResolver != null) {
                return resolveWithAi(document, resolution, searchQuery, diagnostics);
            }
            diagnostics.add("layout resolution LOW_CONFIDENCE (confidence="
                    + resolution.confidence + ") — the layout was not understood, which is not "
                    + "the same as an empty result page");
            return failed(document, resolution.confidence, diagnostics);
        }

        SearchResultBlockDetector.Detection detection = blockDetector.detect(document, resolution);
        diagnostics.addAll(detection.rejectionReasons);
        if (detection.blocks.isEmpty()) {
            if (showsExplicitNoResults(document)) {
                diagnostics.add("explicit no-results indication — genuinely zero organic hits");
                return noOrganicResults(document, resolution.confidence, diagnostics);
            }
            diagnostics.add("a result region was resolved but no valid result block emerged");
            return failed(document, resolution.confidence, diagnostics);
        }
        return assemble(document, resolution.organicResultsContainerId, resolution.confidence,
                detection, diagnostics);
    }

    /**
     * Apply an already-VALIDATED AI layout decision to the existing extraction: the result blocks it
     * named ARE the blocks; only a decision that named none falls back to mechanical detection inside
     * the chosen region. A stale decision (a different snapshot) is refused; a valid decision that
     * yields no result block is EXTRACTION_FAILED — never NO_ORGANIC_RESULTS.
     */
    public SearchResultExtractionResult extract(RenderedPageDocument document,
                                                ValidatedSearchPageLayoutDecision decision) {
        List<String> diagnostics = new ArrayList<String>(document.captureWarnings);
        return withLinkHarvest(document, applyValidatedDecision(document, decision, diagnostics));
    }

    /** Diagnostics access for the technical-details rendering. */
    public SearchPageLayoutResolution resolveLayout(RenderedPageDocument document) {
        return analyzer.analyze(document);
    }

    private SearchResultExtractionResult resolveWithAi(RenderedPageDocument document,
                                                       SearchPageLayoutResolution resolution,
                                                       String searchQuery, List<String> diagnostics) {
        SearchPageAnalysisArtifact artifact =
                artifactBuilder.build(document, resolution, searchQuery);
        SearchPageLayoutResolverResult ai = aiResolver.resolve(new SearchPageLayoutResolutionRequest(
                artifact, settings.aiLayoutResolver, settings.diagnostics, cancellationSignal));
        diagnostics.add("AI layout resolver: " + ai.outcome + " after " + ai.attempts.size()
                + " attempt(s)");
        if (!ai.isResolved()) {
            diagnostics.add("layout resolution LOW_CONFIDENCE and AI " + ai.outcome
                    + " — not an empty result page");
            return failed(document, resolution.confidence, diagnostics);
        }
        return applyValidatedDecision(document, ai.validatedDecision, diagnostics);
    }

    private SearchResultExtractionResult applyValidatedDecision(RenderedPageDocument document,
                                                                ValidatedSearchPageLayoutDecision decision,
                                                                List<String> diagnostics) {
        if (decision == null || !document.snapshotId.equals(decision.snapshotId)) {
            diagnostics.add("stale or missing AI layout decision — refused (snapshot "
                    + (decision == null ? "null" : decision.snapshotId) + " vs document "
                    + document.snapshotId + ")");
            return failed(document, 0, diagnostics);
        }
        Map<String, SearchPageRegionClassification> regions =
                new LinkedHashMap<String, SearchPageRegionClassification>();
        regions.put(decision.primaryOrganicContainerId,
                SearchPageRegionClassification.ORGANIC_RESULTS);
        SearchPageLayoutResolution resolution = new SearchPageLayoutResolution(document.snapshotId,
                decision.primaryOrganicContainerId, decision.confidence, false, regions,
                Collections.<HeuristicScoreBreakdown>emptyList());

        // A decision that NAMED its result blocks is followed. Re-deriving them from the region by
        // repeated-sibling clustering discarded the very answer the repair was asked for — and then
        // failed the page because the cards it had already identified did not repeat often enough.
        SearchResultBlockDetector.Detection detection =
                decision.resultBlockContainerIds.isEmpty()
                        ? blockDetector.detect(document, resolution)
                        : blockDetector.detectExplicit(document, decision.resultBlockContainerIds);
        diagnostics.addAll(detection.rejectionReasons);
        if (detection.blocks.isEmpty()) {
            diagnostics.add("AI-resolved layout produced no valid result block — EXTRACTION_FAILED, "
                    + "not an empty engine");
            return failed(document, decision.confidence, diagnostics);
        }
        return assemble(document, decision.primaryOrganicContainerId, decision.confidence, detection,
                diagnostics);
    }

    /**
     * The single result-block → candidate assembly, shared by the mechanical and AI paths: primary
     * link, snippet, sitelinks and dedup all stay the existing A3 implementation.
     */
    private SearchResultExtractionResult assemble(RenderedPageDocument document,
                                                  String organicContainerId, double confidence,
                                                  SearchResultBlockDetector.Detection detection,
                                                  List<String> diagnostics) {
        List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
        Set<String> seenTargets = new HashSet<String>();
        for (DetectedResultBlock block : detection.blocks) {
            // askai #41: dedup on what the browser will open — the target, or an unresolved redirect's raw href
            // (an empty target must never swallow every other unresolved hit).
            String key = navigationUrlOf(block.primaryLink);
            if (!seenTargets.add(key)) {
                diagnostics.add(block.blockContainerId + ": duplicate target " + key);
                continue;
            }
            List<SearchResultSiteLink> siteLinks = new ArrayList<SearchResultSiteLink>();
            for (RenderedLinkDescriptor siteLink : block.siteLinks) {
                siteLinks.add(new SearchResultSiteLink(navigationUrlOf(siteLink), siteLink.visibleText));
            }
            candidates.add(candidate("candidate-" + candidates.size(), document.snapshotId, block.primaryLink,
                    block.title, block.snippet, block.displayedDomain, block.rank, organicContainerId,
                    block.blockContainerId, block.structuralConfidence, block.primaryLinkConfidence, siteLinks));
        }
        if (candidates.isEmpty()) {
            diagnostics.add("all detected blocks were duplicates — nothing extractable");
            return failed(document, confidence, diagnostics);
        }
        return new SearchResultExtractionResult(SearchPageAnalysisOutcome.ORGANIC_RESULTS,
                document.snapshotId, confidence, candidates, diagnostics);
    }

    /**
     * The SERP-as-JSON safety net: when the STRUCTURED extraction understood too little of the page
     * (fewer candidates than the configured minimum — Bing routinely collapsed to ONE), the page's
     * EXTERNAL links are harvested as additional candidates, each with its visible text and the
     * surrounding excerpt — exactly the {title, url, snippet} shape a SERP API's JSON delivers. The
     * MANDATORY reranker then judges every one of them by that excerpt, so junk links die there, not
     * here. An explicit no-results page stays honestly empty, and a harvest that finds nothing leaves
     * the structured verdict (including EXTRACTION_FAILED and its repair path) untouched.
     */
    private SearchResultExtractionResult withLinkHarvest(RenderedPageDocument document,
                                                         SearchResultExtractionResult structured) {
        int minimum = settings.analysis.linkHarvestMinimumStructuredCandidates;
        if (minimum <= 0
                || structured.outcome == SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS
                || structured.candidates.size() >= minimum) {
            return structured;
        }
        List<SearchResultCandidate> combined =
                new ArrayList<SearchResultCandidate>(structured.candidates);
        Set<String> seenTargets = new HashSet<String>();
        for (SearchResultCandidate candidate : structured.candidates) {
            seenTargets.add(candidate.navigationUrl());
        }
        int externalSeen = 0;
        for (RenderedLinkDescriptor link : document.links) {
            if (combined.size() >= settings.analysis.linkHarvestMaximumCandidates) {
                break;
            }
            String url = navigationUrlOf(link);
            if (!link.visible
                    || link.domainClassification != DomainClassification.EXTERNAL_DOMAIN
                    || !(url.startsWith("http://") || url.startsWith("https://"))) {
                continue;
            }
            if (link.redirectResolutionStatus != LinkRedirectResolution.UNRESOLVED && isHarvestExcluded(url)) {
                continue; // SERP chrome: the engine owner's legal/consent pages are never results
            }
            externalSeen++;
            String title = !link.visibleText.trim().isEmpty()
                    ? link.visibleText.trim() : link.nearestHeadingText.trim();
            if (title.isEmpty() || !seenTargets.add(url)) {
                continue; // nothing to judge it by, or already carried by a structured candidate
            }
            combined.add(candidate("candidate-" + combined.size(), document.snapshotId, link, title,
                    link.surroundingTextExcerpt, link.displayedDomainText, combined.size() + 1,
                    "", link.containerId, 0.0, 0.0,
                    java.util.Collections.<SearchResultSiteLink>emptyList()));
        }
        if (combined.size() <= structured.candidates.size()) {
            List<String> diagnostics = new ArrayList<String>(structured.diagnostics);
            diagnostics.add("link harvest found no additional external links");
            return new SearchResultExtractionResult(structured.outcome, structured.snapshotId,
                    structured.layoutConfidence, structured.candidates, diagnostics);
        }
        List<String> diagnostics = new ArrayList<String>(structured.diagnostics);
        diagnostics.add("link harvest: structured=" + structured.candidates.size() + " → +"
                + (combined.size() - structured.candidates.size()) + " external link candidates (of "
                + externalSeen + " external links; the reranker judges them)");
        return new SearchResultExtractionResult(SearchPageAnalysisOutcome.ORGANIC_RESULTS,
                structured.snapshotId, structured.layoutConfidence, combined, diagnostics);
    }

    /**
     * What the browser opens for a captured link (askai #41): its resolved target, or — for a redirect whose target
     * is not known yet ({@link LinkRedirectResolution#UNRESOLVED}, e.g. Google {@code /goto}) — its raw href.
     */
    static String navigationUrlOf(RenderedLinkDescriptor link) {
        if (link.redirectResolutionStatus == LinkRedirectResolution.UNRESOLVED) {
            return link.rawHref == null ? "" : link.rawHref;
        }
        return link.resolvedTargetUrl == null ? "" : link.resolvedTargetUrl;
    }

    /**
     * The candidate for one primary link, carrying the link's resolution state (askai #41): NOT_A_REDIRECT →
     * DIRECT, RESOLVED → RESOLVED with the wrapper as original URL, UNRESOLVED → no verified target, the raw
     * href is the navigation URL and the provenance.
     */
    private static SearchResultCandidate candidate(String candidateId, String snapshotId, RenderedLinkDescriptor link,
                                                   String title, String snippet, String displayedDomain, int rank,
                                                   String organicContainerId, String blockContainerId,
                                                   double structuralConfidence, double primaryLinkConfidence,
                                                   List<SearchResultSiteLink> siteLinks) {
        UrlResolutionStatus status;
        String target;
        String original;
        switch (link.redirectResolutionStatus) {
            case UNRESOLVED:
                status = UrlResolutionStatus.UNRESOLVED;
                target = "";
                original = link.rawHref;
                break;
            case RESOLVED:
                status = UrlResolutionStatus.RESOLVED;
                target = link.resolvedTargetUrl;
                original = link.rawHref;
                break;
            default:
                status = UrlResolutionStatus.DIRECT;
                target = link.resolvedTargetUrl;
                original = "";
        }
        return new SearchResultCandidate(candidateId, snapshotId, target, link.rawHref, title, snippet,
                displayedDomain, rank, organicContainerId, blockContainerId, structuralConfidence,
                primaryLinkConfidence, siteLinks, "", status, original);
    }

    /** True when the link's host ends in one of the configured excluded (chrome) domains. */
    private boolean isHarvestExcluded(String url) {
        String host = harvestHostOf(url);
        for (String domain : settings.analysis.linkHarvestExcludedDomains) {
            String excluded = domain.trim().toLowerCase(Locale.ROOT);
            if (!excluded.isEmpty()
                    && (host.equals(excluded) || host.endsWith("." + excluded))) {
                return true;
            }
        }
        return false;
    }

    private static String harvestHostOf(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        int colon = hostPort.indexOf(':');
        return (colon < 0 ? hostPort : hostPort.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    private SearchResultExtractionResult failed(RenderedPageDocument document, double confidence,
                                                List<String> diagnostics) {
        return new SearchResultExtractionResult(SearchPageAnalysisOutcome.EXTRACTION_FAILED,
                document.snapshotId, confidence,
                java.util.Collections.<SearchResultCandidate>emptyList(), diagnostics);
    }

    private SearchResultExtractionResult noOrganicResults(RenderedPageDocument document,
                                                          double confidence, List<String> diagnostics) {
        return new SearchResultExtractionResult(SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS,
                document.snapshotId, confidence,
                java.util.Collections.<SearchResultCandidate>emptyList(), diagnostics);
    }

    /** The configured no-results texts against every captured container excerpt (lower-cased). */
    private boolean showsExplicitNoResults(RenderedPageDocument document) {
        for (RenderedContainerDescriptor container : document.containers) {
            String excerpt = container.textExcerpt.toLowerCase(Locale.ROOT);
            for (String marker : settings.analysis.noResultsTexts) {
                if (containsNoResultsMarker(excerpt, marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A marker that starts with a digit ("0 results") only counts as a whole number: inside a larger
     * number it is the tail of a result COUNT, not a no-results statement. Bing's English header
     * "About 1,230,000 results" ends in "0 results" and was read as an empty page — no hit was opened
     * although the page listed results.
     */
    static boolean containsNoResultsMarker(String lowerCasedExcerpt, String marker) {
        if (marker == null || marker.isEmpty()) {
            return false;
        }
        if (!Character.isDigit(marker.charAt(0))) {
            return lowerCasedExcerpt.contains(marker);
        }
        int at = lowerCasedExcerpt.indexOf(marker);
        while (at >= 0) {
            if (at == 0 || !Character.isDigit(lowerCasedExcerpt.charAt(at - 1))) {
                return true;
            }
            at = lowerCasedExcerpt.indexOf(marker, at + 1);
        }
        return false;
    }
}
