package com.aresstack.askai.browser.search.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The complete scored judgement of one container: every signal, per-family sums, the total. */
public final class HeuristicScoreBreakdown {

    public final String containerId;
    public final List<HeuristicSignal> signals;
    public final double totalScore;

    private final Map<SignalFamily, Double> familyScores;

    public HeuristicScoreBreakdown(String containerId, List<HeuristicSignal> signals) {
        this.containerId = containerId;
        this.signals = Collections.unmodifiableList(signals);
        Map<SignalFamily, Double> perFamily = new EnumMap<SignalFamily, Double>(SignalFamily.class);
        double total = 0;
        for (HeuristicSignal signal : signals) {
            total += signal.score;
            Double sum = perFamily.get(signal.family);
            perFamily.put(signal.family, (sum == null ? 0 : sum) + signal.score);
        }
        this.totalScore = total;
        this.familyScores = Collections.unmodifiableMap(perFamily);
    }

    public double familyScore(SignalFamily family) {
        Double score = familyScores.get(family);
        return score == null ? 0 : score;
    }

    /** Families that emitted at least one NONZERO signal — neutral families never discriminate. */
    public Set<SignalFamily> discriminatingFamilies() {
        Set<SignalFamily> families = EnumSet.noneOf(SignalFamily.class);
        for (HeuristicSignal signal : signals) {
            if (signal.score != 0) {
                families.add(signal.family);
            }
        }
        return families;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(containerId + " total=" + totalScore);
        for (HeuristicSignal signal : signals) {
            sb.append("\n  ").append(signal);
        }
        return sb.toString();
    }
}
