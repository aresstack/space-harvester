package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * The IMMUTABLE evidence baseline: which claim revisions and evidence links the user approved, with the
 * contradictions and gaps KNOWN at that moment and every consciously accepted limitation. Approving
 * evidence without a persistable baseline is impossible by construction — the baseline comes first,
 * only then may the workflow open the draft phase.
 */
public final class EvidenceBaseline {

    private final String baselineId;
    private final long outlineRevision;
    private final List<String> includedClaimIds;
    private final List<String> includedEvidenceLinkIds;
    private final List<String> knownContradictionClaimIds;
    private final List<String> knownGapDescriptions;
    private final List<AcceptedLimitation> acceptedLimitations;
    private final Approval approval;

    public EvidenceBaseline(String baselineId, long outlineRevision, List<String> includedClaimIds,
                            List<String> includedEvidenceLinkIds,
                            List<String> knownContradictionClaimIds,
                            List<String> knownGapDescriptions,
                            List<AcceptedLimitation> acceptedLimitations, Approval approval) {
        this.baselineId = baselineId == null ? "" : baselineId;
        this.outlineRevision = outlineRevision;
        this.includedClaimIds = copy(includedClaimIds);
        this.includedEvidenceLinkIds = copy(includedEvidenceLinkIds);
        this.knownContradictionClaimIds = copy(knownContradictionClaimIds);
        this.knownGapDescriptions = copy(knownGapDescriptions);
        this.acceptedLimitations = acceptedLimitations == null
                ? Collections.<AcceptedLimitation>emptyList()
                : Collections.unmodifiableList(
                        new java.util.ArrayList<AcceptedLimitation>(acceptedLimitations));
        this.approval = approval;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public String getBaselineId() {
        return baselineId;
    }

    public long getOutlineRevision() {
        return outlineRevision;
    }

    public List<String> getIncludedClaimIds() {
        return includedClaimIds;
    }

    public List<String> getIncludedEvidenceLinkIds() {
        return includedEvidenceLinkIds;
    }

    public List<String> getKnownContradictionClaimIds() {
        return knownContradictionClaimIds;
    }

    public List<String> getKnownGapDescriptions() {
        return knownGapDescriptions;
    }

    public List<AcceptedLimitation> getAcceptedLimitations() {
        return acceptedLimitations;
    }

    public Approval getApproval() {
        return approval;
    }
}
