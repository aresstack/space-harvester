package com.aresstack.askai.research.domain.scope;

/**
 * The asymmetric OUT case, made unmistakable: a sweep-novel probe at the fringe of an EXCLUDED
 * region is NOT a question candidate — offering it as "soll das auch rein?" would put exactly the
 * things back on the table the user already ruled out. It is drift PROTECTION: the advice layer
 * may at most remind that the exclusion still stands ("private Fitness-Optimierung remains OUT"),
 * and the Z4b chooser is contractually forbidden from turning a guard into a positive question.
 */
public final class ScopeDriftGuard {

    private final String probeText;
    private final String nearestOutAnchorId;
    /** The measured evidence (similarity/novelty note) — why this reads as an OUT fringe. */
    private final String evidence;
    /** How many sweep readings collapsed into this one guard. */
    private final int groupSize;

    public ScopeDriftGuard(String probeText, String nearestOutAnchorId, String evidence,
                           int groupSize) {
        if (probeText == null || probeText.trim().isEmpty()) {
            throw new IllegalArgumentException("probeText must not be empty");
        }
        if (nearestOutAnchorId == null || nearestOutAnchorId.trim().isEmpty()) {
            throw new IllegalArgumentException("a drift guard needs its OUT post");
        }
        this.probeText = probeText.trim();
        this.nearestOutAnchorId = nearestOutAnchorId.trim();
        this.evidence = evidence == null ? "" : evidence;
        this.groupSize = Math.max(1, groupSize);
    }

    public String getProbeText() {
        return probeText;
    }

    public String getNearestOutAnchorId() {
        return nearestOutAnchorId;
    }

    public String getEvidence() {
        return evidence;
    }

    public int getGroupSize() {
        return groupSize;
    }
}
