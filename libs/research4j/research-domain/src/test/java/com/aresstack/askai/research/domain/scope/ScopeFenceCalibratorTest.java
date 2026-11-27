package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.CalibrationParameters;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.CalibrationProbeVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.Confidence;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.FenceCalibration;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.Samples;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The calibration learns EXCLUSIVELY from the negotiated side of the fence: mission relevance and
 * the known-region floor both come from IN/OUT posts (and their local neighborhood controls) —
 * PROVISIONAL, the agent's own hypothesis, calibrates NOTHING while the derived floor still gets
 * APPLIED to it. The floor is never derived from pairwise anchor distances, which on our
 * deliberately multi-island fence measure the spacing BETWEEN regions, not the radius WITHIN one.
 * Coverage is complete-or-WEAK: a floor measured on a subset of islands never calls itself OK.
 * WEAK has one explicit consequence: no hole hunting.
 */
public class ScopeFenceCalibratorTest {

    // Axes: 0=mission, 1=island A, 2=island B (deliberately FAR from A), 3=off-axis noise.
    private static float[] v(float... components) {
        return components;
    }

    /** Two legitimate islands far apart: their pairwise cosine is LOW — that is fence design, not radius. */
    private static List<AnchorVector> anchors() {
        return Arrays.asList(
                new AnchorVector("anchor-helme", ScopeAnchor.Membership.IN, v(0.4f, 1, 0, 0)),
                new AnchorVector("anchor-exo", ScopeAnchor.Membership.OUT, v(0.4f, 0, 1, 0)));
    }

    private static final List<float[]> MISSION =
            Collections.singletonList(v(1, 0.5f, 0.5f, 0));

    private static CalibrationProbeVector control(String id, String parent, float... vector) {
        return new CalibrationProbeVector(new ScopeCalibrationProbe(id, parent, id), vector);
    }

    /** Explicit spike parameters — quantiles/margins/minimums all visible, nothing implied. */
    private static CalibrationParameters parameters(int minimumNegotiated, int minimumNeighbors) {
        return new CalibrationParameters(0.0d, 0.0d, 0.0d, 0.0d,
                minimumNegotiated, minimumNeighbors);
    }

    private static List<CalibrationProbeVector> bothIslandControls() {
        return Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("c2", "anchor-helme", 0.4f, 1, 0, 0.3f),
                control("c3", "anchor-exo", 0.4f, 0.25f, 1, 0),
                control("c4", "anchor-exo", 0.4f, 0, 1, 0.3f));
    }

    @Test
    public void theFloorComesFromNeighborhoodsNeverFromInterIslandDistance() {
        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, bothIslandControls());
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4));

        // The inter-island cosine here is ~0.14 — a floor anywhere near it would be the trap.
        double interIsland = ScopeFenceEvaluator.cosine(v(0.4f, 1, 0, 0), v(0.4f, 0, 1, 0));
        assertTrue("sanity: the islands really are far apart", interIsland < 0.2d);
        assertTrue("the floor reflects LOCAL neighborhood extent (" + calibration.knownRegionFloor
                + "), far above the inter-island distance (" + interIsland + ")",
                calibration.knownRegionFloor > 0.8d);
        assertEquals(Confidence.OK, calibration.confidence);
        assertTrue(calibration.permitsHoleHunting());
        assertEquals("both negotiated anchors contributed a mission-relevance sample",
                2, calibration.samples.anchorMissionRelevances.size());
        assertEquals("every eligible island is covered",
                calibration.samples.eligibleAnchorCount,
                calibration.samples.distinctParentAnchorsCovered);
    }

    /**
     * The self-confirmation trap, both halves: a PROVISIONAL post is the agent's own hypothesis.
     * It calibrates NEITHER the mission (similar probes would read as confirmed) NOR the global
     * known-region floor (its own synthetic neighbors must not shift the measuring stick) — its
     * control is ignored, counted, and it does not enter the coverage denominator. The derived
     * floor still gets APPLIED to provisional relations later (PENDING) — source and application
     * are different questions.
     */
    @Test
    public void aProvisionalPostCalibratesNothingYetStaysMeasurable() {
        List<AnchorVector> anchors = new ArrayList<AnchorVector>(anchors());
        // Hypothetical region pointing at the off-axis the mission barely touches, with an
        // unusually loose synthetic neighbor that would DRAG the q=0 floor down:
        anchors.add(new AnchorVector("anchor-guess", ScopeAnchor.Membership.PROVISIONAL,
                v(0, 0, 0, 1)));
        List<CalibrationProbeVector> controls = new ArrayList<CalibrationProbeVector>(
                bothIslandControls());
        controls.add(control("loose-guess", "anchor-guess", 0.1f, 0.3f, 0.3f, 0.4f));

        Samples samples = ScopeFenceCalibrator.measure(anchors, MISSION, controls);
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4));

        assertEquals("only the two NEGOTIATED posts calibrate the mission",
                2, samples.anchorMissionRelevances.size());
        assertEquals("the guess's loose neighbor is NOT in the floor distribution",
                4, samples.anchorNeighborSimilarities.size());
        assertEquals("ignored, but never silently", 1, samples.provisionalControlsIgnored);
        assertEquals("the guess is no coverage obligation either",
                2, samples.eligibleAnchorCount);
        assertTrue("and so the floor stays a NEGOTIATED-neighborhood measure",
                calibration.knownRegionFloor > 0.8d);
        assertEquals(Confidence.OK, calibration.confidence);
    }

    /**
     * Coverage is complete-or-nothing: the generator produces controls per eligible post anyway,
     * so a floor measured on a SUBSET of the negotiated islands is WEAK — however many samples
     * that subset delivered. No coverage percentage is invented for this.
     */
    @Test
    public void aFloorMeasuredOnASubsetOfIslandsIsWeakHoweverManySamplesItHas() {
        List<AnchorVector> threePosts = Arrays.asList(
                new AnchorVector("anchor-helme", ScopeAnchor.Membership.IN, v(0.4f, 1, 0, 0)),
                new AnchorVector("anchor-exo", ScopeAnchor.Membership.OUT, v(0.4f, 0, 1, 0)),
                new AnchorVector("anchor-lärm", ScopeAnchor.Membership.IN, v(0.4f, 0, 0, 1)));

        Samples partial = ScopeFenceCalibrator.measure(threePosts, MISSION, bothIslandControls());
        FenceCalibration partialCalibration =
                ScopeFenceCalibrator.calibrate(partial, parameters(2, 4));
        assertEquals("two of three islands covered — WEAK, not a global floor",
                Confidence.WEAK, partialCalibration.confidence);
        assertFalse(partialCalibration.permitsHoleHunting());

        List<CalibrationProbeVector> complete = new ArrayList<CalibrationProbeVector>(
                bothIslandControls());
        complete.add(control("c5", "anchor-lärm", 0.4f, 0, 0.25f, 1));
        Samples full = ScopeFenceCalibrator.measure(threePosts, MISSION, complete);
        assertEquals("one control for the third island completes the coverage",
                Confidence.OK, ScopeFenceCalibrator.calibrate(full, parameters(2, 4)).confidence);
    }

    /** Six controls around ONE island measure one region's radius — count never beats coverage. */
    @Test
    public void sixControlsAroundOneIslandAreNoGlobalCalibration() {
        List<CalibrationProbeVector> oneIslandControls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("c2", "anchor-helme", 0.4f, 1, 0, 0.3f),
                control("c3", "anchor-helme", 0.4f, 1, 0.1f, 0.1f),
                control("c4", "anchor-helme", 0.4f, 1, 0.2f, 0.2f),
                control("c5", "anchor-helme", 0.4f, 1, 0.3f, 0),
                control("c6", "anchor-helme", 0.4f, 1, 0, 0.2f));

        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, oneIslandControls);
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4));

        assertEquals("plenty of samples, but all from one island — WEAK",
                Confidence.WEAK, calibration.confidence);
        assertEquals(1, samples.distinctParentAnchorsCovered);
        assertFalse("and WEAK means: no hole-hunt advisory, the dialog just continues",
                calibration.permitsHoleHunting());
    }

    @Test
    public void thinDataDegradesToWeakInsteadOfPretending() {
        List<CalibrationProbeVector> oneControl = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0));
        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, oneControl);

        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4));

        assertEquals("one neighbor sample is no calibration — say so, typed",
                Confidence.WEAK, calibration.confidence);
        assertFalse(calibration.permitsHoleHunting());
    }

    /** Two negotiated posts against a minimum of three: early-phase fences are honestly WEAK. */
    @Test
    public void fewNegotiatedPostsAreWeakHoweverManyControlsArrive() {
        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, bothIslandControls());
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(3, 4));

        assertEquals(Confidence.WEAK, calibration.confidence);
        assertFalse(calibration.permitsHoleHunting());
    }

    /** No mission frame at all: empty samples and WEAK — never OK built on fake 0.0 scores. */
    @Test
    public void missingMissionReferencesAreWeakNeverAWallOfZeros() {
        Samples samples = ScopeFenceCalibrator.measure(anchors(),
                Collections.<float[]>emptyList(), bothIslandControls());

        assertTrue("no fake 0.0 relevance samples", samples.anchorMissionRelevances.isEmpty());
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4));
        assertEquals(Confidence.WEAK, calibration.confidence);
        assertFalse(calibration.permitsHoleHunting());
    }

    @Test
    public void controlsWithoutTheirAnchorAreCountedNeverSilentlyDropped() {
        List<CalibrationProbeVector> controls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("ghost", "anchor-vanished", 0.4f, 0, 0, 1));

        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, controls);

        assertEquals(1, samples.anchorNeighborSimilarities.size());
        assertEquals("the orphan is visible in the samples", 1, samples.orphanedControls);
        assertEquals("an orphan is not a provisional ignore — different diagnosis",
                0, samples.provisionalControlsIgnored);
    }
}
