package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ProbeSweepAnalyzer.ProbeSweepResult;
import com.aresstack.askai.research.domain.scope.ProbeSweepAnalyzer.ProbeVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Z3a sweep on synthetic vectors — every agreed case: an irrelevant far-away probe never wins as
 * a "hole" however novel it is; relevant probes bucket as KNOWN/EXCLUDED/PENDING/BOUNDARY/UNEXPLORED
 * via Z3's OWN cascade (sweep-relative line + explicit calibration floor — never Z2's absolute hint);
 * five wordings of the same unknown topic collapse to ONE diverse candidate; two separate unknown
 * islands both survive; an all-holes sweep still reads as all holes. No coverage percentage exists.
 */
public class ProbeSweepAnalyzerTest {

    // Axes: 0=mission frame, 1=IN region, 2=OUT region, 3=island 1, 4=island 2, 5=fridge world.
    private static float[] v(float... components) {
        return components;
    }

    private static final Thresholds FENCE = new Thresholds(0.5d, 0.1d);
    /** Explicit sweep calibration: relevance floor, boundary margin, sweep-novelty gap, known-region floor. */
    private static final ProbeSweepAnalyzer.SweepParameters SWEEP =
            new ProbeSweepAnalyzer.SweepParameters(0.3d, 0.1d, 0.2d, 0.3d);

    /** The mission frame deliberately spans the whole plausible space — including unexplored axes. */
    private static final List<float[]> MISSION =
            java.util.Collections.singletonList(v(1, 0.4f, 0.4f, 0.5f, 0.5f, 0));

    private static ScopeFenceEvaluator fence() {
        return new ScopeFenceEvaluator(Arrays.asList(
                new AnchorVector("in-sensorik", ScopeAnchor.Membership.IN, v(0.3f, 1, 0, 0, 0, 0)),
                new AnchorVector("out-fitness", ScopeAnchor.Membership.OUT, v(0.3f, 0, 1, 0, 0, 0))));
    }

    private static ProbeVector probe(String id, float... vector) {
        return new ProbeVector(new ScopeProbe(id, id), vector);
    }

    private static ProbeReading readingOf(ProbeSweepResult result, String probeId) {
        for (ProbeReading reading : result.getReadings()) {
            if (reading.getProbe().getProbeId().equals(probeId)) {
                return reading;
            }
        }
        throw new AssertionError("no reading for " + probeId);
    }

    @Test
    public void theTwoAxesBucketEveryAgreedCase() {
        List<ProbeVector> probes = Arrays.asList(
                probe("gaswarnkleidung", 0.3f, 1, 0.1f, 0, 0, 0),      // relevant + near IN
                probe("schrittzaehler", 0.3f, 0.1f, 1, 0, 0, 0),       // relevant + near OUT
                probe("grenzfall", 0.3f, 0.7f, 0.7f, 0, 0, 0),         // relevant + both sides
                probe("exoskelette", 0.4f, 0, 0, 1, 0, 0),             // relevant + far from posts
                probe("kuehlschrank", 0, 0, 0, 0, 0, 1));              // novel but OFF-topic

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fence(), FENCE, SWEEP);

        assertEquals(ProbeReading.Category.KNOWN,
                readingOf(result, "gaswarnkleidung").getCategory());
        assertEquals(ProbeReading.Category.EXCLUDED,
                readingOf(result, "schrittzaehler").getCategory());
        assertEquals(ProbeReading.Category.BOUNDARY,
                readingOf(result, "grenzfall").getCategory());
        assertEquals(ProbeReading.Category.UNEXPLORED,
                readingOf(result, "exoskelette").getCategory());
        assertEquals("off-topic stays off-topic however novel it is",
                ProbeReading.Category.IRRELEVANT,
                readingOf(result, "kuehlschrank").getCategory());
        assertTrue("the fridge IS the most novel — novelty alone must never make it interesting",
                readingOf(result, "kuehlschrank").getNovelty()
                        > readingOf(result, "exoskelette").getNovelty());
        assertEquals("the question-worthy pool is boundary + unexplored only",
                2, result.interesting().size());
        assertEquals(1, result.countOf(ProbeReading.Category.KNOWN));
        assertEquals(1, result.countOf(ProbeReading.Category.IRRELEVANT));
    }

    @Test
    public void fiveWordingsOfTheSameHoleAreOneCandidate_twoIslandsStayTwo() {
        List<ProbeVector> probes = new ArrayList<ProbeVector>();
        // Five near-identical wordings of the exoskeleton hole (island 1)...
        for (int variant = 0; variant < 5; variant++) {
            probes.add(probe("exo-" + variant, 0.4f, 0, 0, 1, 0.02f * variant, 0));
        }
        // ...and ONE genuinely different unknown island (island 2).
        probes.add(probe("alleinarbeiter", 0.4f, 0, 0, 0, 1, 0));

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fence(), FENCE, SWEEP);
        assertEquals("all six are unexplored before diversity",
                6, result.countOf(ProbeReading.Category.UNEXPLORED));

        List<ProbeReading> selected = DiverseProbeSelector.select(
                result.interesting(), ProbeSweepAnalyzer.vectorsById(probes),
                new DiverseProbeSelector.Parameters(5, 1.0d, 0.8d));

        assertEquals("one hole per REGION, not per wording", 2, selected.size());
        boolean island1 = false;
        boolean island2 = false;
        for (ProbeReading candidate : selected) {
            if (candidate.getProbe().getProbeId().startsWith("exo-")) {
                island1 = true;
            }
            if (candidate.getProbe().getProbeId().equals("alleinarbeiter")) {
                island2 = true;
            }
        }
        assertTrue("both separate islands survive", island1 && island2);
    }

    /**
     * The resolved contradiction: a probe ON a provisional post is by definition NOT unexplored —
     * the region was raised, the user just has not decided it. Z2's local hint may still say NOVEL
     * (it ignores provisional posts geometrically); Z3's category must say PENDING.
     */
    @Test
    public void aProbeOnAProvisionalPostIsPendingNeverUnexplored() {
        ScopeFenceEvaluator fenceWithProvisional = new ScopeFenceEvaluator(Arrays.asList(
                new AnchorVector("in-sensorik", ScopeAnchor.Membership.IN, v(0.3f, 1, 0, 0, 0, 0)),
                new AnchorVector("prov-exo", ScopeAnchor.Membership.PROVISIONAL,
                        v(0.3f, 0, 0, 1, 0, 0))));
        List<ProbeVector> probes = Arrays.asList(
                probe("exoskelette", 0.4f, 0, 0, 1, 0, 0),        // right on the provisional post
                probe("alleinarbeiter", 0.4f, 0, 0, 0, 1, 0));    // genuinely unexplored island

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fenceWithProvisional, FENCE, SWEEP);

        ProbeReading onProvisional = readingOf(result, "exoskelette");
        assertEquals("raised-but-undecided is PENDING, never a hole",
                ProbeReading.Category.PENDING, onProvisional.getCategory());
        assertTrue("its fence explanation is in fact excellent",
                onProvisional.getKnownSimilarity() > 0.9d);
        assertEquals("the Z2 hint stays diagnostic — and may well disagree",
                ScopeFenceEvaluator.Hint.NOVEL, onProvisional.getLocalFenceHint());
        assertEquals("the truly unraised island stays UNEXPLORED",
                ProbeReading.Category.UNEXPLORED,
                readingOf(result, "alleinarbeiter").getCategory());
        assertTrue("PENDING is question-worthy material",
                result.interesting().contains(onProvisional));
    }

    /**
     * The cascade-order regression: a probe whose strongest relation is CLEARLY a provisional post
     * must read PENDING even when its fence explanation sits BELOW the sweep-relative unexplored
     * line — a raised region never claims "never mentioned", however sweep-novel it measures. The
     * sweep-relative novelty stays visible on the ORTHOGONAL dimensions (rank, dominant
     * membership); only the advisory category resolves the conflict. Conversely, a provisional
     * post that merely WINS a comparison of three tiny similarities makes nothing PENDING.
     */
    @Test
    public void aSweepNovelProbeDominatedByAProvisionalPostIsPendingNotUnexplored() {
        ScopeFenceEvaluator fenceWithProvisional = new ScopeFenceEvaluator(Arrays.asList(
                new AnchorVector("in-sensorik", ScopeAnchor.Membership.IN, v(0.3f, 1, 0, 0, 0, 0)),
                new AnchorVector("prov-exo", ScopeAnchor.Membership.PROVISIONAL,
                        v(0.3f, 0, 0, 1, 0, 0))));
        List<ProbeVector> probes = new ArrayList<ProbeVector>();
        // Four VERY well explained probes push the sweep median high...
        for (int index = 0; index < 4; index++) {
            probes.add(probe("known-" + index, 0.3f, 1, 0.01f * index, 0, 0, 0));
        }
        // ...one probe clearly dominated by the provisional post, yet BELOW the median-gap line...
        probes.add(probe("exo-neu", 0.3f, 0.45f, 0, 0.85f, 0, 0));
        // ...and one where a provisional similarity merely WINS among rubble (all posts far away).
        probes.add(probe("treibgut", 0.4f, 0.1f, 0.08f, 0.12f, 0.9f, 0));

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(probes, MISSION, fenceWithProvisional,
                FENCE, new ProbeSweepAnalyzer.SweepParameters(0.3d, 0.1d, 0.05d, 0.3d));

        ProbeReading dominated = readingOf(result, "exo-neu");
        assertEquals("raised beats sweep-novel: the category is PENDING",
                ProbeReading.Category.PENDING, dominated.getCategory());
        assertEquals("the raw dimension still says: best explained by the provisional post",
                ScopeAnchor.Membership.PROVISIONAL, dominated.getDominantMembership());
        assertEquals("and the sweep still sees it as the least explained relevant probe but one",
                true, dominated.getSweepNoveltyRank() <= 2);

        ProbeReading rubble = readingOf(result, "treibgut");
        assertEquals("a weak 'winner' among tiny similarities explains nothing — honestly unexplored",
                ProbeReading.Category.UNEXPLORED, rubble.getCategory());
    }

    /**
     * The two kinds of hole are different questions: a probe SUFFICIENTLY tied to a known IN post
     * but unusually weakly explained relative to the sweep is an EXTENSION of that region's edge —
     * never UNEXPLORED ("nie erwähnt"). The orthogonal dimensions say it without contradiction:
     * FenceRelation=IN, NoveltyRelation=SWEEP_NOVEL.
     */
    @Test
    public void aFringeProbeOfAKnownRegionIsExtensionNotUnexplored() {
        List<ProbeVector> probes = new ArrayList<ProbeVector>();
        // Four probes right at the IN post push the sweep median high...
        for (int index = 0; index < 4; index++) {
            probes.add(probe("kern-" + index, 0.3f, 1, 0.01f * index, 0, 0, 0));
        }
        // ...one clearly IN-related probe stretching the region's edge (well above the floor,
        // well below the sweep median).
        probes.add(probe("randfall", 0.3f, 0.6f, 0, 0.6f, 0, 0));

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(probes, MISSION, fence(),
                FENCE, new ProbeSweepAnalyzer.SweepParameters(0.3d, 0.1d, 0.05d, 0.3d));

        ProbeReading fringe = readingOf(result, "randfall");
        assertEquals(ProbeReading.Category.EXTENSION, fringe.getCategory());
        assertEquals(ProbeReading.FenceRelation.IN, fringe.getFenceRelation());
        assertEquals(ProbeReading.NoveltyRelation.SWEEP_NOVEL, fringe.getNoveltyRelation());
        assertEquals("the region's core stays unremarkably KNOWN",
                ProbeReading.Category.KNOWN, readingOf(result, "kern-0").getCategory());
        assertTrue("an extension is question-worthy material",
                result.interesting().contains(fringe));
    }

    /**
     * The symmetric case is deliberately NOT symmetric for Z4: a fringe probe of the OUT region
     * also reads EXTENSION (the advisory reason "a known region's edge" holds for both sides) —
     * but FenceRelation.OUT must survive on the orthogonal dimension, because Z4 must phrase an
     * OUT edge as drift PROTECTION ("still excluded, yes?") and never re-offer it as a positive
     * facet. Losing the side here would silently turn exclusions back into suggestions.
     */
    @Test
    public void anOutFringeStaysRecognizablyOutInsideItsExtensionReading() {
        List<ProbeVector> probes = new ArrayList<ProbeVector>();
        // Four probes right at the OUT post push the sweep median high...
        for (int index = 0; index < 4; index++) {
            probes.add(probe("fitness-" + index, 0.3f, 0.01f * index, 1, 0, 0, 0));
        }
        // ...one clearly OUT-related probe stretching the EXCLUDED region's edge.
        probes.add(probe("wellness-rand", 0.3f, 0, 0.6f, 0, 0.6f, 0));

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(probes, MISSION, fence(),
                FENCE, new ProbeSweepAnalyzer.SweepParameters(0.3d, 0.1d, 0.05d, 0.3d));

        ProbeReading fringe = readingOf(result, "wellness-rand");
        assertEquals("the advisory reason is the shared one: a region's edge",
                ProbeReading.Category.EXTENSION, fringe.getCategory());
        assertEquals("but the SIDE survives orthogonally — Z4's drift protection depends on it",
                ProbeReading.FenceRelation.OUT, fringe.getFenceRelation());
        assertEquals(ProbeReading.NoveltyRelation.SWEEP_NOVEL, fringe.getNoveltyRelation());
        assertEquals("the excluded core stays unremarkably EXCLUDED",
                ProbeReading.Category.EXCLUDED, readingOf(result, "fitness-0").getCategory());
    }

    @Test
    public void anIrrelevantProbeNeverEntersTheSelection() {
        List<ProbeVector> probes = Arrays.asList(
                probe("exoskelette", 0.4f, 0, 0, 1, 0, 0),
                probe("kuehlschrank", 0, 0, 0, 0, 0, 1));
        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fence(), FENCE, SWEEP);

        List<ProbeReading> selected = DiverseProbeSelector.select(
                result.interesting(), ProbeSweepAnalyzer.vectorsById(probes),
                new DiverseProbeSelector.Parameters(5, 1.0d, 0.8d));

        assertEquals(1, selected.size());
        assertEquals("exoskelette", selected.get(0).getProbe().getProbeId());
    }
}
