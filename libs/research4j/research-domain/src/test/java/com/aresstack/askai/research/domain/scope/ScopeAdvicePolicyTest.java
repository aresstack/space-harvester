package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ProbeReading.Category;
import com.aresstack.askai.research.domain.scope.ProbeReading.FenceRelation;
import com.aresstack.askai.research.domain.scope.ProbeReading.NoveltyRelation;
import com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate.Reason;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Hint;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Reading;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Z4a: the deterministic reason-aware layer between "interesting semantic region" and "best next
 * conversational question". Readings group by their CONVERSATIONAL meaning — one open topic per
 * provisional post, one border per (IN,OUT) pair, one edge per accepted region, one candidate per
 * genuinely distinct unexplored island — and the asymmetric OUT case is hard-pinned: the fringe
 * of an exclusion NEVER becomes a positive question again. No cross-reason priority, no combined
 * score, and the advice stays bound to its (revision, fingerprint) snapshot.
 */
public class ScopeAdvicePolicyTest {

    private static final DiverseProbeSelector.Parameters DIVERSITY =
            new DiverseProbeSelector.Parameters(5, 1.0d, 0.8d);

    private final Map<String, float[]> vectors = new LinkedHashMap<String, float[]>();

    private ProbeReading reading(String probeId, String text, Category category,
                                 FenceRelation fenceRelation, NoveltyRelation noveltyRelation,
                                 double missionRelevance, String inId, String outId,
                                 String provisionalId, float[] vector) {
        vectors.put(probeId, vector);
        Reading fence = new Reading(0.6d, inId, 0.5d, outId, 0.55d, provisionalId, 0.1d,
                Hint.NOVEL);
        return new ProbeReading(new ScopeProbe(probeId, text), missionRelevance, fence,
                category, 1, fenceRelation, noveltyRelation);
    }

    private static ProbeSweepAnalyzer.ProbeSweepResult sweep(List<ProbeReading> readings) {
        return new ProbeSweepAnalyzer.ProbeSweepResult(readings);
    }

    @Test
    public void everyReasonGroupsByItsConversationalMeaning() {
        List<ProbeReading> readings = new ArrayList<ProbeReading>();
        // Three PENDING wordings around the SAME provisional post = ONE open topic...
        readings.add(reading("p1", "Exoskelette zur Entlastung", Category.PENDING,
                FenceRelation.PROVISIONAL, NoveltyRelation.WELL_EXPLAINED,
                0.60d, "in-a", "out-a", "prov-exo", new float[]{1, 0, 0, 0}));
        readings.add(reading("p2", "aktive Exoskelette im Lager", Category.PENDING,
                FenceRelation.PROVISIONAL, NoveltyRelation.WELL_EXPLAINED,
                0.70d, "in-a", "out-a", "prov-exo", new float[]{1, 0.1f, 0, 0}));
        readings.add(reading("p3", "Exoskelett-Anzüge", Category.PENDING,
                FenceRelation.PROVISIONAL, NoveltyRelation.WELL_EXPLAINED,
                0.65d, "in-a", "out-a", "prov-exo", new float[]{1, 0, 0.1f, 0}));
        // ...two DIFFERENT boundary pairs = TWO border questions...
        readings.add(reading("b1", "Gesundheitsmonitoring am Bau", Category.BOUNDARY,
                FenceRelation.AMBIGUOUS, NoveltyRelation.WELL_EXPLAINED,
                0.66d, "in-sensorik", "out-fitness", "", new float[]{0, 1, 0, 0}));
        readings.add(reading("b2", "Pulsmessung im Schichtbetrieb", Category.BOUNDARY,
                FenceRelation.AMBIGUOUS, NoveltyRelation.WELL_EXPLAINED,
                0.61d, "in-sensorik", "out-fitness", "", new float[]{0, 1, 0.1f, 0}));
        readings.add(reading("b3", "Ortung versus Privatsphäre", Category.BOUNDARY,
                FenceRelation.AMBIGUOUS, NoveltyRelation.WELL_EXPLAINED,
                0.63d, "in-ortung", "out-tracking", "", new float[]{0, 0.9f, 0.3f, 0}));
        // ...two IN-extensions at the SAME accepted region = ONE edge question...
        readings.add(reading("e1", "AR-Gefahrenvisualisierung", Category.EXTENSION,
                FenceRelation.IN, NoveltyRelation.SWEEP_NOVEL,
                0.72d, "in-sensorik", "out-fitness", "", new float[]{0, 0, 1, 0}));
        readings.add(reading("e2", "AR-Brillen für Inspektionen", Category.EXTENSION,
                FenceRelation.IN, NoveltyRelation.SWEEP_NOVEL,
                0.68d, "in-sensorik", "out-fitness", "", new float[]{0, 0, 1, 0.1f}));
        // ...an OUT-extension = DRIFT GUARD, never a question...
        readings.add(reading("d1", "Wellness-Tracking nach Feierabend", Category.EXTENSION,
                FenceRelation.OUT, NoveltyRelation.SWEEP_NOVEL,
                0.64d, "in-sensorik", "out-fitness", "", new float[]{0, 0, 0.9f, 0.4f}));
        // ...and background noise that must produce nothing.
        readings.add(reading("k1", "Sensorhelme", Category.KNOWN,
                FenceRelation.IN, NoveltyRelation.WELL_EXPLAINED,
                0.80d, "in-sensorik", "out-fitness", "", new float[]{0.5f, 0.5f, 0, 0}));
        readings.add(reading("x1", "Kühlschrankkompressoren", Category.IRRELEVANT,
                FenceRelation.IN, NoveltyRelation.SWEEP_NOVEL,
                0.30d, "in-sensorik", "out-fitness", "", new float[]{0, 0, 0, 1}));

        ScopeAdviceSet advice = ScopeAdvicePolicy.derive(
                sweep(readings), vectors, 17L, "nomic@1", DIVERSITY);

        assertEquals(17L, advice.getScopeRevision());
        assertEquals("nomic@1", advice.getEmbeddingFingerprint());
        assertTrue(advice.appliesTo(17L));
        assertTrue("advice for revision 17 must not apply to revision 18",
                !advice.appliesTo(18L));

        assertEquals("1 open topic + 2 borders + 1 edge = 4 conversational questions",
                4, advice.getQuestionCandidates().size());
        ScopeAdviceCandidate pending = advice.getQuestionCandidates().get(0);
        assertEquals(Reason.RESOLVE_PENDING, pending.getReason());
        assertEquals("three wordings, one open topic", 3, pending.getGroupSize());
        assertEquals("the most mission-relevant wording represents the group",
                "aktive Exoskelette im Lager", pending.getProbeText());
        assertEquals("prov-exo", pending.getNearestProvisionalAnchorId());

        assertEquals(Reason.CLARIFY_BOUNDARY,
                advice.getQuestionCandidates().get(1).getReason());
        assertEquals(Reason.CLARIFY_BOUNDARY,
                advice.getQuestionCandidates().get(2).getReason());
        assertEquals("two wordings of the sensorik/fitness border are ONE question",
                2, advice.getQuestionCandidates().get(1).getGroupSize());

        ScopeAdviceCandidate edge = advice.getQuestionCandidates().get(3);
        assertEquals(Reason.CHECK_IN_EXTENSION, edge.getReason());
        assertEquals("the accepted region's edge, represented by its strongest wording",
                "AR-Gefahrenvisualisierung", edge.getProbeText());

        assertEquals("the OUT fringe is exactly one guard", 1, advice.getDriftGuards().size());
        assertEquals("out-fitness", advice.getDriftGuards().get(0).getNearestOutAnchorId());
    }

    /** The hard pin: an OUT-extension NEVER appears among the question candidates. */
    @Test
    public void anOutExtensionNeverBecomesAQuestionCandidate() {
        List<ProbeReading> readings = new ArrayList<ProbeReading>();
        readings.add(reading("d1", "private Fitness-Optimierung", Category.EXTENSION,
                FenceRelation.OUT, NoveltyRelation.SWEEP_NOVEL,
                0.9d, "in-a", "out-fitness", "", new float[]{1, 0, 0, 0}));

        ScopeAdviceSet advice = ScopeAdvicePolicy.derive(
                sweep(readings), vectors, 1L, "nomic@1", DIVERSITY);

        assertTrue("however mission-relevant: the exclusion's fringe is not re-offered",
                advice.getQuestionCandidates().isEmpty());
        assertEquals(1, advice.getDriftGuards().size());
        assertEquals("private Fitness-Optimierung", advice.getDriftGuards().get(0).getProbeText());
        assertTrue("the guard names its evidence",
                advice.getDriftGuards().get(0).getEvidence().contains("OUT fringe"));
    }

    /** UNEXPLORED is the one genuinely semantic grouping: paraphrases collapse, islands survive. */
    @Test
    public void unexploredParaphrasesCollapseToOneIslandTwoIslandsStayTwo() {
        List<ProbeReading> readings = new ArrayList<ProbeReading>();
        for (int variant = 0; variant < 4; variant++) {
            readings.add(reading("u-exo-" + variant, "Exoskelett-Wortvariante " + variant,
                    Category.UNEXPLORED, FenceRelation.NONE, NoveltyRelation.UNEXPLAINED,
                    0.6d + 0.01d * variant, "", "", "",
                    new float[]{1, 0.02f * variant, 0, 0}));
        }
        readings.add(reading("u-allein", "Alleinarbeiterschutz", Category.UNEXPLORED,
                FenceRelation.NONE, NoveltyRelation.UNEXPLAINED,
                0.62d, "", "", "", new float[]{0, 0, 1, 0}));

        ScopeAdviceSet advice = ScopeAdvicePolicy.derive(
                sweep(readings), vectors, 1L, "nomic@1", DIVERSITY);

        assertEquals("four wordings of one island + one distinct island = two candidates",
                2, advice.getQuestionCandidates().size());
        for (ScopeAdviceCandidate candidate : advice.getQuestionCandidates()) {
            assertEquals(Reason.CHECK_UNEXPLORED, candidate.getReason());
        }
    }

    /** An empty-but-trusted sweep yields empty advice — a legitimate, first-class result. */
    @Test
    public void aSweepWithNothingToTalkAboutYieldsEmptyAdvice() {
        List<ProbeReading> readings = new ArrayList<ProbeReading>();
        readings.add(reading("k1", "Sensorhelme", Category.KNOWN,
                FenceRelation.IN, NoveltyRelation.WELL_EXPLAINED,
                0.8d, "in-a", "out-a", "", new float[]{1, 0, 0, 0}));

        ScopeAdviceSet advice = ScopeAdvicePolicy.derive(
                sweep(readings), vectors, 1L, "nomic@1", DIVERSITY);

        assertTrue(advice.getQuestionCandidates().isEmpty());
        assertTrue(advice.getDriftGuards().isEmpty());
    }
}
