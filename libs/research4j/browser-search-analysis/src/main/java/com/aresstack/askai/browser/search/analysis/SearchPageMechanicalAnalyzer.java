package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mechanical SERP understanding (A3b): classifies regions coarsely, scores every plausible
 * container through the {@link SearchPageContainerScorer} and picks the best ORGANIC_RESULTS
 * candidate. All thresholds and weights come from the settings snapshot. The center prior is a
 * PRIOR, not truth — a left-aligned result list with strong structural signals beats a centered
 * non-result region. If fewer than the configured minimum of signal families discriminate, or no
 * candidate reaches the minimum structural confidence, the resolution is LOW_CONFIDENCE — never a
 * guessed container and never a flat link list.
 */
public final class SearchPageMechanicalAnalyzer {

    private final LegacyBrowserSearchSettings settings;
    private final SearchPageContainerScorer scorer;

    public SearchPageMechanicalAnalyzer(LegacyBrowserSearchSettings settings) {
        this.settings = settings;
        this.scorer = new SearchPageContainerScorer(settings.analysis, settings.visualAnalysis);
    }

    public SearchPageLayoutResolution analyze(RenderedPageDocument document) {
        Map<String, SearchPageRegionClassification> regions =
                new LinkedHashMap<String, SearchPageRegionClassification>();
        List<RenderedContainerDescriptor> candidates =
                new ArrayList<RenderedContainerDescriptor>();
        for (RenderedContainerDescriptor container : document.containers) {
            SearchPageRegionClassification region = classify(container);
            regions.put(container.containerId, region);
            // The root (body) is the whole page, never a result region: the decision validator
            // refuses it as FULL_PAGE_SELECTION, so offering it only let a small model pick it on
            // every repair attempt.
            if (region == SearchPageRegionClassification.UNKNOWN && container.visible
                    && !container.parentContainerId.isEmpty()
                    && container.linkCount > 0
                    && container.totalTextLength
                            >= settings.analysis.minimumContainerTextCharacters) {
                candidates.add(container);
            }
        }

        List<HeuristicScoreBreakdown> scored = new ArrayList<HeuristicScoreBreakdown>();
        for (RenderedContainerDescriptor candidate : candidates) {
            scored.add(scorer.score(candidate, document));
        }
        Collections.sort(scored, new Comparator<HeuristicScoreBreakdown>() {
            public int compare(HeuristicScoreBreakdown a, HeuristicScoreBreakdown b) {
                return Double.compare(b.totalScore, a.totalScore);
            }
        });
        List<String> capped = new ArrayList<String>();
        if (scored.size() > settings.analysis.maximumCandidateContainers) {
            for (HeuristicScoreBreakdown dropped
                    : scored.subList(settings.analysis.maximumCandidateContainers, scored.size())) {
                capped.add(dropped.containerId);
            }
            scored = new ArrayList<HeuristicScoreBreakdown>(
                    scored.subList(0, settings.analysis.maximumCandidateContainers));
        }

        if (scored.isEmpty()) {
            return new SearchPageLayoutResolution(document.snapshotId, "", 0, true, regions, scored,
                    capped);
        }
        HeuristicScoreBreakdown best = scored.get(0);
        double confidence = confidenceOf(best);
        boolean lowConfidence =
                best.discriminatingFamilies().size()
                        < settings.analysis.minimumDiscriminatingSignalFamilies
                || confidence < settings.analysis.minimumResultStructuralConfidence;
        if (lowConfidence) {
            return new SearchPageLayoutResolution(document.snapshotId, "", confidence, true,
                    regions, scored, capped);
        }
        regions.put(best.containerId, SearchPageRegionClassification.ORGANIC_RESULTS);
        return new SearchPageLayoutResolution(document.snapshotId, best.containerId, confidence,
                false, regions, scored, capped);
    }

    /**
     * Normalized confidence: the total score against the settings-derived "strong result" score
     * (the sum of the positive result-defining weights) — no magic normalization constants.
     */
    private double confidenceOf(HeuristicScoreBreakdown breakdown) {
        double strongResultScore = settings.analysis.repeatedBlockWeight
                + settings.analysis.titleLinkWeight
                + settings.analysis.snippetPresenceWeight
                + settings.analysis.nonLinkTextWeight
                + settings.analysis.externalLinkWeight
                + settings.analysis.semanticMainWeight;
        if (strongResultScore <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, breakdown.totalScore / strongResultScore));
    }

    /**
     * Coarse per-container region classification from DOM semantics and naming — deliberately
     * conservative: only unambiguous markers classify; everything else stays UNKNOWN and competes
     * as an organic candidate. Non-content is NOT automatically navigation.
     */
    SearchPageRegionClassification classify(RenderedContainerDescriptor c) {
        if (c.semanticFlags.contains("NAV")) {
            return SearchPageRegionClassification.NAVIGATION;
        }
        if (c.semanticFlags.contains("FOOTER")) {
            return SearchPageRegionClassification.FOOTER;
        }
        if (c.semanticFlags.contains("FORM")) {
            return SearchPageRegionClassification.SEARCH_CONTROLS;
        }
        if (c.semanticFlags.contains("ASIDE")) {
            return SearchPageRegionClassification.AUXILIARY_CONTENT;
        }
        String tokens = nameTokens(c);
        if (containsToken(tokens, "pagination") || containsToken(tokens, "paging")
                || containsToken(tokens, "pager")) {
            return SearchPageRegionClassification.PAGINATION;
        }
        if (namedAsAdvertisement(tokens)) {
            return SearchPageRegionClassification.ADVERTISEMENT;
        }
        if (containsToken(tokens, "filter") || containsToken(tokens, "filters")
                || containsToken(tokens, "facet") || containsToken(tokens, "facets")) {
            return SearchPageRegionClassification.FILTERS;
        }
        return SearchPageRegionClassification.UNKNOWN;
    }

    /** An element whose id, class or role names it an ad ("ad", "ads", "advert", "sponsor(ed)"). */
    static boolean namedAsAdvertisement(RenderedContainerDescriptor c) {
        return namedAsAdvertisement(nameTokens(c));
    }

    private static boolean namedAsAdvertisement(String tokens) {
        return containsToken(tokens, "ad") || containsToken(tokens, "ads")
                || containsToken(tokens, "advert") || containsToken(tokens, "sponsored")
                || containsToken(tokens, "sponsor");
    }

    private static String nameTokens(RenderedContainerDescriptor c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.elementId.toLowerCase(Locale.ROOT));
        for (String token : c.classTokens) {
            sb.append(' ').append(token.toLowerCase(Locale.ROOT));
        }
        sb.append(' ').append(c.role.toLowerCase(Locale.ROOT));
        return sb.toString();
    }

    /** Whole-token containment (split on non-letters) — "ad" must not match "header" or "badge". */
    private static boolean containsToken(String tokens, String needle) {
        for (String token : tokens.split("[^a-z]+")) {
            if (token.equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
