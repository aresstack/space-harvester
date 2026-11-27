package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;
import com.aresstack.askai.browser.search.layout.EngineFamily;
import com.aresstack.askai.browser.search.layout.MechanicalConfidenceOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageSignalScore;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Projects a {@link RenderedPageDocument} and its {@link SearchPageLayoutResolution} into the
 * neutral, BOUNDED {@link SearchPageAnalysisArtifact} (A4a). Only the mechanically scored candidates
 * become choosable container descriptors; text excerpts are capped by
 * {@link SearchDiagnosticsSettings#maximumTextExcerptCharacters}; region-classified containers become
 * bounded rejection reasons. No raw HTML, screenshots, cookies, headers or credentials ever enter the
 * artifact — the analyzer already delivered only neutral facts and this only narrows them further.
 */
public final class SearchPageAnalysisArtifactBuilder {

    /** Upper bound on synthesized rejection-reason lines, independent of artifact-byte budget. */
    private static final int MAX_REJECTION_REASONS = 64;

    private final LegacyBrowserSearchSettings settings;

    public SearchPageAnalysisArtifactBuilder(LegacyBrowserSearchSettings settings) {
        this.settings = settings;
    }

    public SearchPageAnalysisArtifact build(RenderedPageDocument document,
                                            SearchPageLayoutResolution resolution,
                                            String searchQuery) {
        SearchDiagnosticsSettings diagnostics = settings.diagnostics;
        int excerptLimit = Math.max(0, diagnostics.maximumTextExcerptCharacters);

        List<SearchPageContainerCandidate> candidates =
                new ArrayList<SearchPageContainerCandidate>();
        for (HeuristicScoreBreakdown breakdown : resolution.scoredCandidates) {
            RenderedContainerDescriptor descriptor = document.container(breakdown.containerId);
            if (descriptor == null) {
                continue;
            }
            candidates.add(project(descriptor, breakdown, excerptLimit,
                    ancestrySignature(document, descriptor.containerId)));
        }

        List<String> preferred = new ArrayList<String>();
        if (resolution.hasOrganicResultsContainer()) {
            preferred.add(resolution.organicResultsContainerId);
        } else {
            for (SearchPageContainerCandidate candidate : candidates) {
                preferred.add(candidate.containerId);
            }
        }

        List<String> rejections = rejectionReasons(resolution);

        MechanicalConfidenceOutcome outcome = resolution.lowConfidence
                ? MechanicalConfidenceOutcome.LOW_CONFIDENCE
                : MechanicalConfidenceOutcome.HIGH_CONFIDENCE;

        return new SearchPageAnalysisArtifact(
                "analysis-" + document.snapshotId + "-" + document.snapshotGeneration,
                document.snapshotId, document.snapshotGeneration,
                document.documentFingerprint == null ? "" : document.documentFingerprint.value,
                searchQuery, EngineFamily.fromUrlOrHost(document.pageUrl), document.pageUrl,
                document.pageTitle, outcome, resolution.confidence, preferred, candidates, rejections,
                new ArrayList<String>(document.captureWarnings), digest());
    }

    private SearchPageContainerCandidate project(RenderedContainerDescriptor c,
                                                 HeuristicScoreBreakdown breakdown, int excerptLimit,
                                                 String ancestrySignature) {
        List<SearchPageSignalScore> scores = new ArrayList<SearchPageSignalScore>();
        for (SignalFamily family : SignalFamily.values()) {
            scores.add(new SearchPageSignalScore(family.name(), breakdown.familyScore(family)));
        }
        return new SearchPageContainerCandidate(
                c.containerId, c.parentContainerId, c.tagName, c.role,
                new ArrayList<String>(c.semanticFlags), cap(c.textExcerpt, excerptLimit),
                c.totalTextLength, c.nonLinkTextLength, c.headingCount, c.linkCount,
                c.sameHostLinkCount, c.sameRegistrableDomainLinkCount, c.externalDomainLinkCount,
                c.boundingBox, c.viewportIntersectionRatio, c.containsViewportCenter,
                c.horizontalCenterDistance, c.verticalCenterDistance, c.backgroundDistanceToParent,
                c.backgroundDistanceToPage, c.borderSummary, !c.boxShadow.isEmpty(),
                c.structureSignature == null ? "" : c.structureSignature.value, c.similarSiblingCount,
                ancestrySignature, scores, breakdown.totalScore, "");
    }

    /** Tag-name ancestry from the root down to the container, e.g. {@code body>div>main}. */
    private static String ancestrySignature(RenderedPageDocument document, String containerId) {
        List<String> chain = new ArrayList<String>();
        String current = containerId;
        java.util.Set<String> visited = new java.util.LinkedHashSet<String>();
        while (current != null && !current.isEmpty() && visited.add(current)) {
            RenderedContainerDescriptor descriptor = document.container(current);
            if (descriptor == null) {
                break;
            }
            chain.add(descriptor.tagName.isEmpty() ? "?" : descriptor.tagName);
            current = descriptor.parentContainerId;
        }
        java.util.Collections.reverse(chain);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append('>');
            }
            sb.append(chain.get(i));
        }
        return sb.toString();
    }

    /**
     * Bounded, human-readable reasons a container was NOT offered as an organic candidate — only the
     * unambiguous mechanical region classifications, never raw content.
     */
    /**
     * Why a container is NOT an offered candidate — the artifact's diagnostic vocabulary, read back by
     * {@link SearchPageLayoutDecisionValidator} when it has to explain a layout violation. Two prefixes,
     * both stable: {@code "<id> dropped by candidate cap"} and {@code "<id> classified as <REGION>"}.
     */
    static final String DROPPED_BY_CAP_SUFFIX = " dropped by candidate cap";
    static final String CLASSIFIED_AS_INFIX = " classified as ";

    private List<String> rejectionReasons(SearchPageLayoutResolution resolution) {
        List<String> reasons = new ArrayList<String>();
        // Capped containers come FIRST: they are the ones a layout decision can trip over, and the
        // bound below must never be the reason we cannot explain a violation.
        for (String capped : resolution.cappedCandidateIds) {
            if (reasons.size() >= MAX_REJECTION_REASONS) {
                break;
            }
            reasons.add(capped + DROPPED_BY_CAP_SUFFIX);
        }
        for (Map.Entry<String, SearchPageRegionClassification> entry
                : resolution.regionByContainerId.entrySet()) {
            SearchPageRegionClassification region = entry.getValue();
            if (region == SearchPageRegionClassification.UNKNOWN
                    || region == SearchPageRegionClassification.ORGANIC_RESULTS) {
                continue;
            }
            if (reasons.size() >= MAX_REJECTION_REASONS) {
                reasons.add("… further region classifications omitted (limit "
                        + MAX_REJECTION_REASONS + ")");
                break;
            }
            reasons.add(entry.getKey() + CLASSIFIED_AS_INFIX + region.name());
        }
        return reasons;
    }

    private static String cap(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return limit <= 1 ? value.substring(0, Math.max(0, limit)) : value.substring(0, limit - 1) + "…";
    }

    /**
     * A stable, secret-free digest of the settings that shaped this artifact: mechanical thresholds
     * plus the diagnostics bounds. Same settings → same digest, so a stored layout profile can refuse
     * reuse when the settings that validated it have changed.
     */
    private String digest() {
        StringBuilder sb = new StringBuilder();
        sb.append("minConf=").append(settings.analysis.minimumResultStructuralConfidence);
        sb.append(";minFam=").append(settings.analysis.minimumDiscriminatingSignalFamilies);
        sb.append(";maxCand=").append(settings.analysis.maximumCandidateContainers);
        sb.append(";minRep=").append(settings.analysis.minimumRepeatedSiblingCount);
        sb.append(";excerpt=").append(settings.diagnostics.maximumTextExcerptCharacters);
        sb.append(";artBytes=").append(settings.diagnostics.maximumDiagnosticArtifactBytes);
        sb.append(";redact=").append(settings.diagnostics.redactUrls);
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(Charset.forName("UTF-8")));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int v = b & 0xff;
                if (v < 0x10) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(v));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JLS; fall back to a stable non-crypto digest.
            return Integer.toHexString(value.hashCode());
        }
    }
}
