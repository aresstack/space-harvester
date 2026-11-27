package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Turns the sweep's question-worthy pool into FEW, genuinely different candidates: fifteen wordings
 * of the same unknown topic are ONE hole, not fifteen. Greedy MMR-style selection — the best
 * (relevance + weighted novelty) candidate first, then only candidates that do not nearly mean the
 * same as an already selected one. All weights and ceilings are EXPLICIT parameters; nothing here
 * is a fachlich fixed formula yet.
 */
public final class DiverseProbeSelector {

    /** The selection knobs — calibration inputs of the caller, never hidden constants. */
    public static final class Parameters {
        /** How many diverse candidates the agent gets at most. */
        public final int maximumCandidates;
        /** Weight of fence novelty against mission relevance in the ranking score. */
        public final double noveltyWeight;
        /** A candidate this cosine-similar to an already selected one is the SAME region — skipped. */
        public final double duplicateSimilarityCeiling;

        public Parameters(int maximumCandidates, double noveltyWeight,
                          double duplicateSimilarityCeiling) {
            this.maximumCandidates = Math.max(1, maximumCandidates);
            this.noveltyWeight = noveltyWeight;
            this.duplicateSimilarityCeiling = duplicateSimilarityCeiling;
        }
    }

    private DiverseProbeSelector() {
    }

    /**
     * @param pool          question-worthy readings (UNEXPLORED/BOUNDARY — IRRELEVANT never enters)
     * @param vectorsById   the probes' vectors (see {@link ProbeSweepAnalyzer#vectorsById})
     */
    public static List<ProbeReading> select(List<ProbeReading> pool,
                                            Map<String, float[]> vectorsById,
                                            Parameters parameters) {
        List<ProbeReading> ranked = new ArrayList<ProbeReading>(pool);
        Collections.sort(ranked, (a, b) -> Double.compare(score(b, parameters), score(a, parameters)));
        List<ProbeReading> selected = new ArrayList<ProbeReading>();
        for (ProbeReading candidate : ranked) {
            if (selected.size() >= parameters.maximumCandidates) {
                break;
            }
            float[] vector = vectorsById.get(candidate.getProbe().getProbeId());
            if (vector == null) {
                continue; // no vector, no comparable region — never guessed
            }
            boolean duplicate = false;
            for (ProbeReading already : selected) {
                float[] other = vectorsById.get(already.getProbe().getProbeId());
                if (other != null && ScopeFenceEvaluator.cosine(vector, other)
                        >= parameters.duplicateSimilarityCeiling) {
                    duplicate = true; // the same unknown region in different words
                    break;
                }
            }
            if (!duplicate) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    /** relevance + λ·novelty — an off-topic probe loses however novel it is. */
    private static double score(ProbeReading reading, Parameters parameters) {
        return reading.getMissionRelevance() + parameters.noveltyWeight * reading.getNovelty();
    }
}
