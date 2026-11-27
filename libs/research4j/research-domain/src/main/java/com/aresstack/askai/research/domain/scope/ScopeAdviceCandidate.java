package com.aresstack.askai.research.domain.scope;

/**
 * ONE question-worthy conversational finding derived from a sweep — Z4a's unit. The REASON is the
 * primary dimension: PENDING, BOUNDARY, IN-EXTENSION and UNEXPLORED are four DIFFERENT
 * conversational situations (an already-raised-but-undecided point, a genuine IN/OUT border, the
 * edge of an accepted region, a genuinely new island), and they are deliberately NOT pressed into
 * one score — a lower-scoring PENDING can matter more than the third exciting EXTENSION, and that
 * judgement belongs to the Z4b chooser (and ultimately the user), never to an invented weight.
 * <p>
 * A candidate represents a GROUP of readings that mean the same conversational question (several
 * wordings around one provisional post, one boundary pair, one region's edge); {@code groupSize}
 * keeps that compression visible. OUT-extensions never become candidates — they are
 * {@link ScopeDriftGuard}s.
 */
public final class ScopeAdviceCandidate {

    /** WHY this is worth a question — the four conversational situations, never a ranking. */
    public enum Reason {
        /** Already raised via a provisional post, still undecided — clarify it. */
        RESOLVE_PENDING,
        /** A probe caught between a negotiated IN and OUT post — clarify the border. */
        CLARIFY_BOUNDARY,
        /** The fringe of an ACCEPTED region — ask whether the edge belongs too. */
        CHECK_IN_EXTENSION,
        /** A plausible island no post explains — offer the genuinely new topic. */
        CHECK_UNEXPLORED
    }

    private final String candidateId;
    private final Reason reason;
    private final String probeText;
    private final String nearestInAnchorId;
    private final String nearestOutAnchorId;
    private final String nearestProvisionalAnchorId;
    private final double missionRelevance;
    private final double knownSimilarity;
    private final int sweepNoveltyRank;
    /** How many sweep readings collapsed into this ONE conversational question. */
    private final int groupSize;

    public ScopeAdviceCandidate(String candidateId, Reason reason, String probeText,
                                String nearestInAnchorId, String nearestOutAnchorId,
                                String nearestProvisionalAnchorId, double missionRelevance,
                                double knownSimilarity, int sweepNoveltyRank, int groupSize) {
        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("candidateId must not be empty");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (probeText == null || probeText.trim().isEmpty()) {
            throw new IllegalArgumentException("probeText must not be empty");
        }
        this.candidateId = candidateId.trim();
        this.reason = reason;
        this.probeText = probeText.trim();
        this.nearestInAnchorId = nearestInAnchorId == null ? "" : nearestInAnchorId;
        this.nearestOutAnchorId = nearestOutAnchorId == null ? "" : nearestOutAnchorId;
        this.nearestProvisionalAnchorId =
                nearestProvisionalAnchorId == null ? "" : nearestProvisionalAnchorId;
        this.missionRelevance = missionRelevance;
        this.knownSimilarity = knownSimilarity;
        this.sweepNoveltyRank = sweepNoveltyRank;
        this.groupSize = Math.max(1, groupSize);
    }

    public String getCandidateId() {
        return candidateId;
    }

    public Reason getReason() {
        return reason;
    }

    /** The representative probe wording of this group. */
    public String getProbeText() {
        return probeText;
    }

    public String getNearestInAnchorId() {
        return nearestInAnchorId;
    }

    public String getNearestOutAnchorId() {
        return nearestOutAnchorId;
    }

    public String getNearestProvisionalAnchorId() {
        return nearestProvisionalAnchorId;
    }

    public double getMissionRelevance() {
        return missionRelevance;
    }

    public double getKnownSimilarity() {
        return knownSimilarity;
    }

    public int getSweepNoveltyRank() {
        return sweepNoveltyRank;
    }

    public int getGroupSize() {
        return groupSize;
    }
}
