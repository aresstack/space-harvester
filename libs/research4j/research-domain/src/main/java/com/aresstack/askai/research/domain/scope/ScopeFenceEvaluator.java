package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Z2 of the Weidezaun: pure fence GEOMETRY over embedded anchors — no I/O, no model, no workflow.
 * <p>
 * For one probe vector it measures the proximity to the negotiated fence posts (IN / PROVISIONAL /
 * OUT anchors) and derives a classification HINT. The numbers decide NOTHING by themselves: this
 * evaluator produces observations for the scoping conversation (which single question is worth
 * asking), never a readiness verdict and never a workflow transition — the user owns the state
 * machine, this class does not even know it exists.
 * <p>
 * Semantics follow the agreed concept: {@code sIn = max cos(probe, IN)}, {@code sOut = max
 * cos(probe, OUT)}, {@code margin = sIn - sOut}. PROVISIONAL posts are reported but never decide a
 * margin — an unconfirmed post is a hypothesis, not a boundary. OUT anchors are real posts (kept,
 * never negation sentences); a probe close to OUT is honestly LIKELY_OUT.
 */
public final class ScopeFenceEvaluator {

    /** One embedded fence post: stable id + membership + its vector (a DERIVED projection). */
    public static final class AnchorVector {
        public final String anchorId;
        public final ScopeAnchor.Membership membership;
        final float[] vector;

        public AnchorVector(String anchorId, ScopeAnchor.Membership membership, float[] vector) {
            if (anchorId == null || anchorId.trim().isEmpty()) {
                throw new IllegalArgumentException("anchorId must not be empty");
            }
            if (membership == null) {
                throw new IllegalArgumentException("membership must not be null");
            }
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException("vector must not be empty");
            }
            this.anchorId = anchorId.trim();
            this.membership = membership;
            this.vector = vector.clone();
        }
    }

    /** The classification HINT — an observation for the conversation, never a decision. */
    public enum Hint {
        /** Clearly closer to the IN posts than to any OUT post. */
        LIKELY_IN,
        /** Clearly closer to a known OUT post — near something the user already ruled out. */
        LIKELY_OUT,
        /** Close to BOTH sides: it is genuinely unclear where this belongs — worth ONE question. */
        BOUNDARY,
        /** Far from every post: an unexplored region — possibly a missing fence post. */
        NOVEL
    }

    /** The measured reading for one probe: raw values first, the hint last. */
    public static final class Reading {
        public final double nearestInSimilarity;
        public final String nearestInAnchorId;
        public final double nearestOutSimilarity;
        public final String nearestOutAnchorId;
        public final double nearestProvisionalSimilarity;
        public final String nearestProvisionalAnchorId;
        /** {@code sIn - sOut}; positive = leaning IN. 0 contributions for absent sides. */
        public final double margin;
        public final Hint hint;

        Reading(double nearestInSimilarity, String nearestInAnchorId,
                double nearestOutSimilarity, String nearestOutAnchorId,
                double nearestProvisionalSimilarity, String nearestProvisionalAnchorId,
                double margin, Hint hint) {
            this.nearestInSimilarity = nearestInSimilarity;
            this.nearestInAnchorId = nearestInAnchorId;
            this.nearestOutSimilarity = nearestOutSimilarity;
            this.nearestOutAnchorId = nearestOutAnchorId;
            this.nearestProvisionalSimilarity = nearestProvisionalSimilarity;
            this.nearestProvisionalAnchorId = nearestProvisionalAnchorId;
            this.margin = margin;
            this.hint = hint;
        }
    }

    /**
     * The classification cut-offs — EXPLICIT parameters, never hidden constants. What "near" and
     * "clearly" mean is a calibration decision that belongs to the caller (and ultimately to a
     * setting), not to this math.
     */
    public static final class Thresholds {
        /** A similarity at or above this counts as "near a post". */
        public final double nearSimilarity;
        /** An |margin| below this, while near both sides, is genuinely ambiguous (BOUNDARY). */
        public final double boundaryMargin;

        public Thresholds(double nearSimilarity, double boundaryMargin) {
            this.nearSimilarity = nearSimilarity;
            this.boundaryMargin = boundaryMargin;
        }
    }

    private final List<AnchorVector> anchors;

    public ScopeFenceEvaluator(List<AnchorVector> anchors) {
        this.anchors = Collections.unmodifiableList(new ArrayList<AnchorVector>(
                anchors == null ? Collections.<AnchorVector>emptyList() : anchors));
    }

    /** Measure one probe vector against the fence. Never throws for an empty fence: everything is NOVEL. */
    public Reading evaluate(float[] probeVector, Thresholds thresholds) {
        if (probeVector == null || probeVector.length == 0) {
            throw new IllegalArgumentException("probeVector must not be empty");
        }
        if (thresholds == null) {
            throw new IllegalArgumentException("thresholds must not be null");
        }
        double bestIn = Double.NaN;
        String bestInId = null;
        double bestOut = Double.NaN;
        String bestOutId = null;
        double bestProvisional = Double.NaN;
        String bestProvisionalId = null;
        for (AnchorVector anchor : anchors) {
            double similarity = cosine(probeVector, anchor.vector);
            switch (anchor.membership) {
                case IN:
                    if (Double.isNaN(bestIn) || similarity > bestIn) {
                        bestIn = similarity;
                        bestInId = anchor.anchorId;
                    }
                    break;
                case OUT:
                    if (Double.isNaN(bestOut) || similarity > bestOut) {
                        bestOut = similarity;
                        bestOutId = anchor.anchorId;
                    }
                    break;
                case PROVISIONAL:
                    if (Double.isNaN(bestProvisional) || similarity > bestProvisional) {
                        bestProvisional = similarity;
                        bestProvisionalId = anchor.anchorId;
                    }
                    break;
                default:
                    break;
            }
        }
        double sIn = Double.isNaN(bestIn) ? 0.0d : bestIn;
        double sOut = Double.isNaN(bestOut) ? 0.0d : bestOut;
        double margin = sIn - sOut;
        Hint hint;
        boolean nearIn = sIn >= thresholds.nearSimilarity;
        boolean nearOut = sOut >= thresholds.nearSimilarity;
        if (!nearIn && !nearOut) {
            hint = Hint.NOVEL; // far from every post — the interesting case for the conversation
        } else if (Math.abs(margin) < thresholds.boundaryMargin) {
            hint = Hint.BOUNDARY; // near the fence but on no clear side — worth ONE question
        } else {
            hint = margin > 0 ? Hint.LIKELY_IN : Hint.LIKELY_OUT;
        }
        return new Reading(sIn, bestInId, sOut, bestOutId,
                Double.isNaN(bestProvisional) ? 0.0d : bestProvisional, bestProvisionalId,
                margin, hint);
    }

    /** Cosine similarity; vectors of different length are a caller error and fail loudly. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vector dimensions differ: " + a.length
                    + " vs " + b.length + " (mixed embedding models?)");
        }
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
