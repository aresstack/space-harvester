package com.aresstack.askai.research.domain;

/**
 * One evidence relation between a claim and a persisted passage. Excluding evidence never deletes the
 * link — it moves to {@link Lifecycle#EXCLUDED} and stays traceable (a conscious user decision).
 */
public final class EvidenceLink {

    private final String linkId;
    private final String claimId;
    private final String passageId;
    private final EvidenceRelation relation;
    private final double relevance;
    private final double reliability;
    private final double confidence;
    private final Lifecycle status;

    public EvidenceLink(String linkId, String claimId, String passageId, EvidenceRelation relation,
                        double relevance, double reliability, double confidence, Lifecycle status) {
        this.linkId = linkId == null ? "" : linkId;
        this.claimId = claimId == null ? "" : claimId;
        this.passageId = passageId == null ? "" : passageId;
        this.relation = relation == null ? EvidenceRelation.PROVIDES_CONTEXT : relation;
        this.relevance = relevance;
        this.reliability = reliability;
        this.confidence = confidence;
        this.status = status == null ? Lifecycle.PROPOSED : status;
    }

    public EvidenceLink withStatus(Lifecycle newStatus) {
        return new EvidenceLink(linkId, claimId, passageId, relation, relevance, reliability,
                confidence, newStatus);
    }

    public String getLinkId() {
        return linkId;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getPassageId() {
        return passageId;
    }

    public EvidenceRelation getRelation() {
        return relation;
    }

    public double getRelevance() {
        return relevance;
    }

    public double getReliability() {
        return reliability;
    }

    public double getConfidence() {
        return confidence;
    }

    public Lifecycle getStatus() {
        return status;
    }
}
