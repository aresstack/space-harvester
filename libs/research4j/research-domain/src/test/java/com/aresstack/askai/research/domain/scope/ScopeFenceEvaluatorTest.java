package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Hint;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Reading;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Weidezaun geometry, proven on synthetic vectors: several IN islands, an OUT hole inside an
 * otherwise close region, boundary ambiguity and true novelty. The evaluator only MEASURES —
 * classification hints come from explicit thresholds, and nothing here knows the workflow.
 */
public class ScopeFenceEvaluatorTest {

    /** Vectors on distinct axes with small blends — controllable cosine geometry. */
    private static float[] v(float... components) {
        return components;
    }

    private static final Thresholds T = new Thresholds(0.5d, 0.1d);

    /** Two IN islands far apart from each other, one OUT post near island B — a non-convex fence. */
    private ScopeFenceEvaluator fence() {
        return new ScopeFenceEvaluator(Arrays.asList(
                // Island A around axis 0 (e.g. "Schutzhelme mit Sensorik")
                new AnchorVector("in-helme", ScopeAnchor.Membership.IN, v(1, 0, 0, 0)),
                // Island B around axis 1 (e.g. "Gasdetektions-Wearables")
                new AnchorVector("in-gas", ScopeAnchor.Membership.IN, v(0, 1, 0, 0)),
                // OUT post CLOSE to island B (e.g. "Consumer-Fitnesstracker" neben Sensorik)
                new AnchorVector("out-fitness", ScopeAnchor.Membership.OUT, v(0, 0.6f, 0.8f, 0)),
                // A provisional post on its own axis — a hypothesis, not a boundary
                new AnchorVector("prov-exo", ScopeAnchor.Membership.PROVISIONAL, v(0, 0, 0, 1))));
    }

    @Test
    public void aProbeInsideAnIslandIsLikelyInWithTheRightNearestPost() {
        Reading reading = fence().evaluate(v(0.95f, 0.05f, 0, 0), T);
        assertEquals(Hint.LIKELY_IN, reading.hint);
        assertEquals("in-helme", reading.nearestInAnchorId);
        assertTrue("margin leans clearly IN", reading.margin > 0.3d);
    }

    @Test
    public void bothIslandsCountAsInside_theFenceIsNotOneCircle() {
        // Near island B — far from island A, yet clearly IN: non-convex regions work.
        Reading reading = new ScopeFenceEvaluator(Arrays.asList(
                new AnchorVector("in-helme", ScopeAnchor.Membership.IN, v(1, 0, 0, 0)),
                new AnchorVector("in-gas", ScopeAnchor.Membership.IN, v(0, 1, 0, 0))))
                .evaluate(v(0, 0.9f, 0.1f, 0), T);
        assertEquals(Hint.LIKELY_IN, reading.hint);
        assertEquals("in-gas", reading.nearestInAnchorId);
    }

    @Test
    public void aProbeAtTheOutPostIsLikelyOutEvenThoughAnInIslandIsNear() {
        // The "hole in the fence": close to island B, but CLOSEST to the negotiated OUT post.
        Reading reading = fence().evaluate(v(0, 0.55f, 0.83f, 0), T);
        assertEquals(Hint.LIKELY_OUT, reading.hint);
        assertEquals("out-fitness", reading.nearestOutAnchorId);
        assertTrue("margin leans OUT", reading.margin < 0);
    }

    @Test
    public void betweenInAndOutTheReadingIsHonestlyAmbiguous() {
        // Halfway between island B and the OUT post: near BOTH, margin tiny → BOUNDARY.
        Reading reading = fence().evaluate(v(0, 0.9f, 0.45f, 0), T);
        assertEquals(Hint.BOUNDARY, reading.hint);
        assertTrue(Math.abs(reading.margin) < 0.1d);
    }

    @Test
    public void farFromEveryPostMeansNovel_theInterestingCase() {
        // Orthogonal to all IN/OUT posts (only the provisional one is nearby — it never decides).
        Reading reading = fence().evaluate(v(0, 0, 0, 1), T);
        assertEquals(Hint.NOVEL, reading.hint);
        assertEquals("the provisional hypothesis is reported for the conversation",
                "prov-exo", reading.nearestProvisionalAnchorId);
        assertTrue(reading.nearestProvisionalSimilarity > 0.9d);
    }

    @Test
    public void anEmptyFenceMakesEverythingNovel() {
        Reading reading = new ScopeFenceEvaluator(
                Collections.<AnchorVector>emptyList()).evaluate(v(1, 0, 0, 0), T);
        assertEquals(Hint.NOVEL, reading.hint);
        assertEquals(0.0d, reading.margin, 0.0001d);
    }

    @Test
    public void provisionalPostsNeverDecideTheMargin() {
        // Probe right at a PROVISIONAL post: without confirmed IN/OUT it stays NOVEL — an
        // unconfirmed hypothesis is not a fence side.
        Reading reading = new ScopeFenceEvaluator(Collections.singletonList(
                new AnchorVector("prov", ScopeAnchor.Membership.PROVISIONAL, v(1, 0, 0, 0))))
                .evaluate(v(1, 0, 0, 0), T);
        assertEquals(Hint.NOVEL, reading.hint);
        assertEquals(0.0d, reading.margin, 0.0001d);
    }

    @Test
    public void mixedEmbeddingDimensionsFailLoudly() {
        try {
            new ScopeFenceEvaluator(Collections.singletonList(
                    new AnchorVector("a", ScopeAnchor.Membership.IN, v(1, 0))))
                    .evaluate(v(1, 0, 0), T);
            throw new AssertionError("dimension mismatch must fail loudly, not score nonsense");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("dimensions differ"));
        }
    }
}
