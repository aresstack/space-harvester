package com.aresstack.askai.browser.search.analysis;

/** One scored observation of one signal family; the rationale makes diagnostics readable. */
public final class HeuristicSignal {

    public final SignalFamily family;
    public final String name;
    /** Signed contribution to the container score (weights come from the settings). */
    public final double score;
    public final String rationale;

    public HeuristicSignal(SignalFamily family, String name, double score, String rationale) {
        this.family = family;
        this.name = name;
        this.score = score;
        this.rationale = rationale == null ? "" : rationale;
    }

    @Override
    public String toString() {
        return family + "/" + name + "=" + score + (rationale.isEmpty() ? "" : " (" + rationale + ")");
    }
}
