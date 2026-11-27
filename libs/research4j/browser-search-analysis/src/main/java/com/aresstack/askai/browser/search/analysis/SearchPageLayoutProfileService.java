package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.SearchResultExtractionSettings;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfile;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileMatch;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileQuery;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationResult;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

import java.util.Collections;

/**
 * Applies and records validated layout profiles by STRUCTURE, never by container id (A4e). It works
 * purely from the bounded artifact — the runtime that consults it never holds the RenderedPageDocument
 * — so every candidate carries its own structure and ancestry signature. Reuse is only permitted when
 * the engine family, fingerprint, settings digest and structure/ancestry signatures are compatible AND
 * a current candidate re-resolves to that structure AND the re-derived selection re-validates against
 * the current artifact. The id is re-derived from the CURRENT artifact, never resurrected from storage.
 */
public final class SearchPageLayoutProfileService {

    static final int STRUCTURE_SIGNATURE_VERSION = FileSearchPageLayoutProfileStore
            .STRUCTURE_SIGNATURE_VERSION;

    private final SearchPageLayoutDecisionValidator validator;

    public SearchPageLayoutProfileService(SearchResultExtractionSettings extraction) {
        this.validator = new SearchPageLayoutDecisionValidator(extraction);
    }

    /**
     * Try to resolve the current low-confidence layout from a stored profile without calling the AI.
     * Returns the re-derived, re-validated decision, or null when no compatible profile re-resolves.
     * A used profile is re-saved with a bumped validation stamp.
     */
    public ValidatedSearchPageLayoutDecision resolveFromProfiles(SearchPageAnalysisArtifact artifact,
                                                                 SearchPageLayoutProfileStore store,
                                                                 long nowEpochMillis) {
        for (SearchPageContainerCandidate candidate : artifact.containerCandidates) {
            SearchPageLayoutProfileQuery query = new SearchPageLayoutProfileQuery(
                    artifact.engineFamily, artifact.documentFingerprint, artifact.settingsDigest,
                    candidate.structureSignature, candidate.ancestrySignature);
            SearchPageLayoutProfileMatch match = store.find(query);
            if (!match.matched) {
                continue;
            }
            SearchPageLayoutResolutionDecision decision = new SearchPageLayoutResolutionDecision(
                    artifact.analysisId, artifact.snapshotId,
                    Collections.singletonList(candidate.containerId),
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    profileConfidence(match.profile), "reused validated layout profile");
            SearchPageLayoutValidationResult validation = validator.validate(decision, artifact);
            if (!validation.valid) {
                continue; // profile no longer re-validates against the current structure — drop it
            }
            store.saveValidated(match.profile.revalidated(nowEpochMillis));
            return validator.toValidatedDecision(decision, artifact);
        }
        return null;
    }

    /**
     * Build a STRUCTURE-only profile from a validated decision on the current artifact — the region
     * structure signature and ancestry, never the snapshot-local container ids.
     */
    public SearchPageLayoutProfile buildProfile(SearchPageAnalysisArtifact artifact,
                                                ValidatedSearchPageLayoutDecision decision,
                                                long nowEpochMillis) {
        SearchPageContainerCandidate region = candidateOf(artifact, decision.primaryOrganicContainerId);
        String regionSignature = region == null ? "" : region.structureSignature;
        String ancestry = region == null ? "" : region.ancestrySignature;
        return new SearchPageLayoutProfile(artifact.engineFamily, artifact.documentFingerprint,
                STRUCTURE_SIGNATURE_VERSION, regionSignature,
                Collections.<String>emptyList(), ancestry, artifact.settingsDigest, nowEpochMillis,
                nowEpochMillis, 1);
    }

    private static SearchPageContainerCandidate candidateOf(SearchPageAnalysisArtifact artifact,
                                                            String containerId) {
        for (SearchPageContainerCandidate candidate : artifact.containerCandidates) {
            if (candidate.containerId.equals(containerId)) {
                return candidate;
            }
        }
        return null;
    }

    private static double profileConfidence(SearchPageLayoutProfile profile) {
        // A revalidated structural profile is a strong-but-not-certain prior.
        return 0.75;
    }
}
