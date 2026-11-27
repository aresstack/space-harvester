package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.SearchResultExtractionSettings;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationResult;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationViolation;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationViolation.Kind;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, purely-structural validation of a raw model layout decision against its artifact (A4c). The
 * hard invariant is absolute: a container id the mechanics did not offer is NEVER accepted — it is a
 * validation failure that may trigger a bounded repair retry, never a silently applied guess. The
 * model's free-text explanation is ignored entirely. On success it produces the
 * {@link ValidatedSearchPageLayoutDecision} the extractor may apply.
 */
final class SearchPageLayoutDecisionValidator {

    private final SearchResultExtractionSettings extraction;

    SearchPageLayoutDecisionValidator(SearchResultExtractionSettings extraction) {
        this.extraction = extraction;
    }

    /**
     * Complete a decision with what the DOM already says, before judging it.
     * <p>
     * The contract asks for two things about one fact: name the result card, and separately name the
     * region it sits in. But which container a card sits in is not a judgement — it is in the artifact,
     * next to the card, as {@code parent=}. Demanding it from the model turned a structural lookup into
     * a chance to fail, and the live case failed it three times in a row: the model kept naming the same
     * correct block and was rejected for not repeating its parent.
     * <p>
     * So the direct parent of every named result block is derived here and put FIRST among the organic
     * regions — but only when the DOM supports it: the parent must be a container the mechanics offered,
     * must not be the root, and must not be one the model itself excluded. Where that does not hold,
     * nothing is promoted and NOTHING is rejected for it: the region is provenance about where a card
     * sits, never a condition for the card counting as a result.
     */
    SearchPageLayoutResolutionDecision normalize(SearchPageLayoutResolutionDecision decision,
                                                 SearchPageAnalysisArtifact artifact) {
        if (decision == null || decision.resultBlockContainerIds.isEmpty()) {
            return decision;
        }
        Map<String, String> parentById = parentIndex(artifact);
        Set<String> excluded = new HashSet<String>(decision.excludedContainerIds);
        List<String> derived = new ArrayList<String>();
        for (String block : decision.resultBlockContainerIds) {
            String parent = parentById.get(block);
            if (parent == null || parent.isEmpty()
                    || !parentById.containsKey(parent)      // never offered → not choosable
                    || isRoot(parent, parentById)           // a full-page container is not a region
                    || excluded.contains(parent)            // the model ruled it out itself
                    || derived.contains(parent)
                    || decision.organicResultContainerIds.contains(parent)) {
                continue;
            }
            derived.add(parent);
        }
        if (derived.isEmpty()) {
            return decision;
        }
        List<String> organic = new ArrayList<String>(derived);
        organic.addAll(decision.organicResultContainerIds);
        return new SearchPageLayoutResolutionDecision(decision.analysisId, decision.snapshotId,
                organic, decision.resultBlockContainerIds, decision.excludedContainerIds,
                decision.confidence, decision.explanation);
    }

    /** A container with no known parent is the page itself, never a result region. */
    private static boolean isRoot(String containerId, Map<String, String> parentById) {
        String parent = parentById.get(containerId);
        return parent == null || parent.isEmpty();
    }

    SearchPageLayoutValidationResult validate(SearchPageLayoutResolutionDecision decision,
                                              SearchPageAnalysisArtifact artifact) {
        List<SearchPageLayoutValidationViolation> violations =
                new ArrayList<SearchPageLayoutValidationViolation>();

        if (!artifact.snapshotId.equals(decision.snapshotId)) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.UNKNOWN_SNAPSHOT,
                    "decision snapshot '" + decision.snapshotId + "' does not match artifact snapshot '"
                            + artifact.snapshotId + "'"));
        }
        if (!artifact.analysisId.equals(decision.analysisId)) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.ANALYSIS_MISMATCH,
                    "decision analysis '" + decision.analysisId + "' does not match artifact analysis '"
                            + artifact.analysisId + "'"));
        }

        Map<String, String> parentById = parentIndex(artifact);

        checkKnown(decision.organicResultContainerIds, "organicResultContainerIds", artifact,
                violations);
        checkKnown(decision.resultBlockContainerIds, "resultBlockContainerIds", artifact, violations);
        checkKnown(decision.excludedContainerIds, "excludedContainerIds", artifact, violations);

        checkDuplicates(decision.organicResultContainerIds, "organicResultContainerIds", violations);
        checkDuplicates(decision.resultBlockContainerIds, "resultBlockContainerIds", violations);
        checkDuplicates(decision.excludedContainerIds, "excludedContainerIds", violations);

        Set<String> organic = new HashSet<String>(decision.organicResultContainerIds);
        Set<String> blocks = new HashSet<String>(decision.resultBlockContainerIds);
        for (String id : decision.excludedContainerIds) {
            if (organic.contains(id)) {
                violations.add(new SearchPageLayoutValidationViolation(
                        Kind.CONTRADICTORY_CLASSIFICATION,
                        "container '" + id + "' is both organic and excluded"));
            }
            if (blocks.contains(id)) {
                violations.add(new SearchPageLayoutValidationViolation(
                        Kind.CONTRADICTORY_CLASSIFICATION,
                        "container '" + id + "' is both a result block and excluded"));
            }
        }

        if (decision.organicResultContainerIds.isEmpty()
                && decision.resultBlockContainerIds.isEmpty()) {
            // Naming NOTHING is still no answer. But a decision that named the result cards has said
            // where the results are; it does not owe a second, coarser way of saying the same thing.
            violations.add(new SearchPageLayoutValidationViolation(Kind.NO_ORGANIC_CONTAINER,
                    "neither an organic result container nor a result block was named"));
        }

        if (decision.confidence < 0.0 || decision.confidence > 1.0
                || Double.isNaN(decision.confidence)) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.INVALID_CONFIDENCE,
                    "confidence " + decision.confidence + " is outside 0..1"));
        }

        int maxOrganic = Math.max(1, artifact.containerCandidates.size());
        if (decision.organicResultContainerIds.size() > maxOrganic) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.LIMIT_EXCEEDED,
                    "organic container count " + decision.organicResultContainerIds.size()
                            + " exceeds the candidate count " + maxOrganic));
        }
        if (decision.resultBlockContainerIds.size() > extraction.maximumExtractedCandidates) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.LIMIT_EXCEEDED,
                    "result block count " + decision.resultBlockContainerIds.size()
                            + " exceeds the configured maximum "
                            + extraction.maximumExtractedCandidates));
        }

        // Root/full-page selection: a top-level container (no known parent) is never a result region.
        for (String id : decision.organicResultContainerIds) {
            if (parentById.containsKey(id)) {
                String parent = parentById.get(id);
                if (parent == null || parent.isEmpty()) {
                    violations.add(new SearchPageLayoutValidationViolation(Kind.FULL_PAGE_SELECTION,
                            "organic container '" + id + "' is a root/full-page container"));
                }
            }
        }

        // A named result block is NOT required to sit directly inside a named region.
        //
        // That rule belonged to the world where a repair only pointed at a region and the mechanical
        // detector then had to rediscover the cards inside it. Since the extractor follows the named
        // blocks (detectExplicit), the region relation proves nothing the block itself does not: the
        // block is bound to this snapshot by checkKnown, must not be excluded, and is then verified
        // where it matters — visible, with a qualifying primary external link, or no candidate.
        // Live, the model named the same correct card three times and was rejected three times for
        // not also naming its parent, which the artifact already stated.

        return new SearchPageLayoutValidationResult(violations);
    }

    /**
     * Builds the validated, safe-to-apply decision from an already-valid raw decision. The first
     * organic container becomes the primary region the extractor seeds its block detection with.
     */
    ValidatedSearchPageLayoutDecision toValidatedDecision(SearchPageLayoutResolutionDecision decision,
                                                          SearchPageAnalysisArtifact artifact) {
        // The extraction context. A decision that named only blocks has no region to point at, so the
        // first block stands for itself — it is provenance, and it never decides what is extracted.
        String primary = !decision.organicResultContainerIds.isEmpty()
                ? decision.organicResultContainerIds.get(0)
                : (decision.resultBlockContainerIds.isEmpty()
                        ? "" : decision.resultBlockContainerIds.get(0));
        // Bind the trusted values from the ARTIFACT, not from the model — the model only chose ids.
        return new ValidatedSearchPageLayoutDecision(artifact.analysisId, artifact.snapshotId,
                artifact.snapshotGeneration, artifact.documentFingerprint, artifact.settingsDigest,
                primary, decision.organicResultContainerIds, decision.resultBlockContainerIds,
                decision.excludedContainerIds, decision.confidence);
    }

    private void checkKnown(List<String> ids, String field, SearchPageAnalysisArtifact artifact,
                            List<SearchPageLayoutValidationViolation> violations) {
        for (String id : ids) {
            if (!artifact.knowsContainer(id)) {
                violations.add(new SearchPageLayoutValidationViolation(Kind.UNKNOWN_CONTAINER_ID,
                        field + " references unknown container id '" + id + "'"));
            }
        }
    }

    private void checkDuplicates(List<String> ids, String field,
                                 List<SearchPageLayoutValidationViolation> violations) {
        Set<String> seen = new HashSet<String>();
        for (String id : ids) {
            if (!seen.add(id)) {
                violations.add(new SearchPageLayoutValidationViolation(Kind.DUPLICATE_ID,
                        field + " lists '" + id + "' more than once"));
            }
        }
    }

    /** The parent's coarse region, read from the artifact's rejection vocabulary; UNKNOWN when unlisted. */
    private static String regionOf(String containerId, SearchPageAnalysisArtifact artifact) {
        String marker = containerId + SearchPageAnalysisArtifactBuilder.CLASSIFIED_AS_INFIX;
        for (String reason : artifact.mechanicalRejectionReasons) {
            if (reason.startsWith(marker)) {
                return reason.substring(marker.length());
            }
        }
        return "UNKNOWN";
    }

    /** 1-based position in the score-ordered candidate list, or {@code not-ranked} when not offered. */
    private static String rankOf(String containerId, SearchPageAnalysisArtifact artifact) {
        for (int i = 0; i < artifact.containerCandidates.size(); i++) {
            if (artifact.containerCandidates.get(i).containerId.equals(containerId)) {
                return Integer.toString(i + 1);
            }
        }
        return "not-ranked";
    }

    private static boolean droppedByCap(String containerId, SearchPageAnalysisArtifact artifact) {
        return artifact.mechanicalRejectionReasons.contains(
                containerId + SearchPageAnalysisArtifactBuilder.DROPPED_BY_CAP_SUFFIX);
    }

    private Map<String, String> parentIndex(SearchPageAnalysisArtifact artifact) {
        Map<String, String> parentById = new HashMap<String, String>();
        for (SearchPageContainerCandidate candidate : artifact.containerCandidates) {
            parentById.put(candidate.containerId, candidate.parentContainerId);
        }
        return parentById;
    }
}
