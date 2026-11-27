package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Z3's hole finder: runs a batch of embedded probes against the coarse mission frame AND the fence —
 * two orthogonal axes measured separately, then bucketed advisory. The fence relationship comes
 * EXCLUSIVELY from {@link ScopeFenceEvaluator} (Z2 stays exactly as small as it is); the mission
 * relevance from the draft's mission/domains/contexts reference texts. Mission relevance never
 * decides IN/OUT — a consumer fitness tracker can be highly relevant to "Wearables" and still be
 * EXCLUDED by a negotiated OUT post.
 * <p>
 * There is NO absolute novelty cutoff and NO coverage percentage here: a sweep with zero UNEXPLORED
 * findings means only "this sweep found nothing new" — saturation EVIDENCE grows over several broad
 * sweeps, it is never computed as a magic number.
 */
public final class ProbeSweepAnalyzer {

    /** One embedded probe — the sweep's working unit (vectors come from one batch embed call). */
    public static final class ProbeVector {
        public final ScopeProbe probe;
        final float[] vector;

        public ProbeVector(ScopeProbe probe, float[] vector) {
            if (probe == null) {
                throw new IllegalArgumentException("probe must not be null");
            }
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException("vector must not be empty");
            }
            this.probe = probe;
            this.vector = vector.clone();
        }
    }

    /** The whole sweep, raw readings first — counts are a FINDING, never a completion verdict. */
    public static final class ProbeSweepResult {
        private final List<ProbeReading> readings;
        private final Map<ProbeReading.Category, Integer> counts;

        ProbeSweepResult(List<ProbeReading> readings) {
            this.readings = Collections.unmodifiableList(readings);
            Map<ProbeReading.Category, Integer> tally =
                    new EnumMap<ProbeReading.Category, Integer>(ProbeReading.Category.class);
            for (ProbeReading reading : readings) {
                Integer sofar = tally.get(reading.getCategory());
                tally.put(reading.getCategory(), sofar == null ? 1 : sofar + 1);
            }
            this.counts = Collections.unmodifiableMap(tally);
        }

        public List<ProbeReading> getReadings() {
            return readings;
        }

        public int countOf(ProbeReading.Category category) {
            Integer count = counts.get(category);
            return count == null ? 0 : count;
        }

        /**
         * The question-worthy pool: UNEXPLORED (missing island), EXTENSION (a known region's
         * edge), BOUNDARY (sides conflict) and PENDING (raised but undecided) — four DIFFERENT
         * kinds of question, in reading order.
         */
        public List<ProbeReading> interesting() {
            List<ProbeReading> pool = new ArrayList<ProbeReading>();
            for (ProbeReading reading : readings) {
                if (reading.getCategory() == ProbeReading.Category.UNEXPLORED
                        || reading.getCategory() == ProbeReading.Category.BOUNDARY
                        || reading.getCategory() == ProbeReading.Category.PENDING
                        || reading.getCategory() == ProbeReading.Category.EXTENSION) {
                    pool.add(reading);
                }
            }
            return pool;
        }
    }

    private ProbeSweepAnalyzer() {
    }

    /**
     * The sweep's classification inputs — ALL explicit calibration parameters of the caller, never
     * hidden constants. {@code knownRegionFloor} is the calibrated boundary "sufficiently explained
     * by SOME existing post" — the absolute reference this axis cannot avoid needing (a sweep alone
     * cannot see that EVERYTHING is a hole); Z3b derives it from the negotiated anchors, and it
     * must never become a hard-coded fachlich number. {@code sweepNoveltyGap} is the RELATIVE part:
     * a sufficiently known probe explained unusually worse than this sweep's median reads as
     * SWEEP_NOVEL (an EXTENSION candidate), never as unexplored.
     */
    public static final class SweepParameters {
        public final double minimumMissionRelevance;
        public final double boundaryMargin;
        public final double sweepNoveltyGap;
        public final double knownRegionFloor;

        public SweepParameters(double minimumMissionRelevance, double boundaryMargin,
                               double sweepNoveltyGap, double knownRegionFloor) {
            this.minimumMissionRelevance = minimumMissionRelevance;
            this.boundaryMargin = boundaryMargin;
            this.sweepNoveltyGap = sweepNoveltyGap;
            this.knownRegionFloor = knownRegionFloor;
        }
    }

    /**
     * Measure every probe on THREE orthogonal dimensions — mission relevance, fence relation,
     * novelty relation — and derive the advisory category from them. Relevanz ≠ Zaunbeziehung ≠
     * relative Neuheit: pressing them into one number is exactly what this modelling avoids.
     * <pre>
     *   IRRELEVANT = not mission-relevant
     *   UNEXPLORED = relevant, UNEXPLAINED (below the known-region floor for EVERY post)
     *   PENDING    = relevant, sufficiently known, provisional-dominant
     *   BOUNDARY   = relevant, sufficiently known, genuine IN/OUT ambiguity
     *   EXTENSION  = relevant, sufficiently known, but SWEEP_NOVEL — a known region's edge
     *   KNOWN      = relevant, sufficiently known, IN-dominant, unremarkable
     *   EXCLUDED   = relevant, sufficiently known, OUT-dominant
     * </pre>
     * A probe with a sufficient relation to ANY existing post can never read "never mentioned",
     * however sweep-novel it measures — that difference is EXTENSION, a different question for the
     * user than a genuinely missing island.
     */
    public static ProbeSweepResult analyze(List<ProbeVector> probes,
                                           List<float[]> missionReferenceVectors,
                                           ScopeFenceEvaluator fence,
                                           ScopeFenceEvaluator.Thresholds fenceThresholds,
                                           SweepParameters parameters) {
        // Pass 1: raw measurements on both axes.
        List<double[]> axes = new ArrayList<double[]>(); // [relevance]
        List<ScopeFenceEvaluator.Reading> fenceReadings =
                new ArrayList<ScopeFenceEvaluator.Reading>();
        List<Double> relevantKnownSimilarities = new ArrayList<Double>();
        for (ProbeVector probe : probes) {
            double relevance = 0.0d;
            for (float[] reference : missionReferenceVectors) {
                relevance = Math.max(relevance, ScopeFenceEvaluator.cosine(probe.vector, reference));
            }
            ScopeFenceEvaluator.Reading fenceReading = fence.evaluate(probe.vector, fenceThresholds);
            axes.add(new double[]{relevance});
            fenceReadings.add(fenceReading);
            if (relevance >= parameters.minimumMissionRelevance) {
                relevantKnownSimilarities.add(knownSimilarityOf(fenceReading));
            }
        }
        // Pass 2: the sweep-relative novelty line — this SWEEP's distribution, not a constant.
        double median = median(relevantKnownSimilarities);
        double sweepNoveltyLine = median - parameters.sweepNoveltyGap;
        // Pass 3: buckets + sweep-relative novelty ranks.
        List<Double> rankedKnown = new ArrayList<Double>(relevantKnownSimilarities);
        Collections.sort(rankedKnown);
        List<ProbeReading> readings = new ArrayList<ProbeReading>();
        for (int index = 0; index < probes.size(); index++) {
            double relevance = axes.get(index)[0];
            ScopeFenceEvaluator.Reading fenceReading = fenceReadings.get(index);
            double known = knownSimilarityOf(fenceReading);
            // The orthogonal dimensions FIRST — they hold for every probe, relevant or not.
            ProbeReading.FenceRelation fenceRelation =
                    fenceRelationOf(fenceReading, parameters.boundaryMargin);
            boolean sufficientlyKnown = known >= parameters.knownRegionFloor
                    && fenceRelation != ProbeReading.FenceRelation.NONE;
            ProbeReading.NoveltyRelation noveltyRelation = !sufficientlyKnown
                    ? ProbeReading.NoveltyRelation.UNEXPLAINED
                    : (known < sweepNoveltyLine
                            ? ProbeReading.NoveltyRelation.SWEEP_NOVEL
                            : ProbeReading.NoveltyRelation.WELL_EXPLAINED);
            ProbeReading.Category category;
            int rank = 0;
            if (relevance < parameters.minimumMissionRelevance) {
                category = ProbeReading.Category.IRRELEVANT;
            } else {
                rank = rankedKnown.indexOf(known) + 1; // 1 = least explained in THIS sweep
                if (noveltyRelation == ProbeReading.NoveltyRelation.UNEXPLAINED) {
                    category = ProbeReading.Category.UNEXPLORED;
                } else if (fenceRelation == ProbeReading.FenceRelation.PROVISIONAL) {
                    category = ProbeReading.Category.PENDING;
                } else if (fenceRelation == ProbeReading.FenceRelation.AMBIGUOUS) {
                    category = ProbeReading.Category.BOUNDARY;
                } else if (noveltyRelation == ProbeReading.NoveltyRelation.SWEEP_NOVEL) {
                    category = ProbeReading.Category.EXTENSION;
                } else {
                    category = fenceRelation == ProbeReading.FenceRelation.IN
                            ? ProbeReading.Category.KNOWN : ProbeReading.Category.EXCLUDED;
                }
            }
            readings.add(new ProbeReading(probes.get(index).probe, relevance, fenceReading,
                    category, rank, fenceRelation, noveltyRelation));
        }
        return new ProbeSweepResult(readings);
    }

    /** Which post type relates to this probe; AMBIGUOUS = genuine IN/OUT conflict. */
    private static ProbeReading.FenceRelation fenceRelationOf(
            ScopeFenceEvaluator.Reading reading, double boundaryMargin) {
        double in = reading.nearestInSimilarity;
        double out = reading.nearestOutSimilarity;
        double provisional = reading.nearestProvisionalSimilarity;
        if (in <= 0.0d && out <= 0.0d && provisional <= 0.0d) {
            return ProbeReading.FenceRelation.NONE;
        }
        if (provisional >= in && provisional >= out) {
            return ProbeReading.FenceRelation.PROVISIONAL;
        }
        if (Math.abs(reading.margin) < boundaryMargin) {
            return ProbeReading.FenceRelation.AMBIGUOUS;
        }
        return in >= out ? ProbeReading.FenceRelation.IN : ProbeReading.FenceRelation.OUT;
    }

    private static double knownSimilarityOf(ScopeFenceEvaluator.Reading reading) {
        return Math.max(reading.nearestInSimilarity,
                Math.max(reading.nearestOutSimilarity, reading.nearestProvisionalSimilarity));
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0d;
    }

    /** The probe vectors by id — what the diversity selection needs to compare candidates. */
    public static Map<String, float[]> vectorsById(List<ProbeVector> probes) {
        Map<String, float[]> byId = new LinkedHashMap<String, float[]>();
        for (ProbeVector probe : probes) {
            byId.put(probe.probe.getProbeId(), probe.vector);
        }
        return byId;
    }
}
