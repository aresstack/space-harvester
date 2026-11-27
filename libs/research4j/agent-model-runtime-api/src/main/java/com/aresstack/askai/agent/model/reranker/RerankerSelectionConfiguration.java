package com.aresstack.askai.agent.model.reranker;

import java.util.OptionalDouble;

/**
 * The MODEL-BOUND candidate-selection policy: a required Top-N plus purely OPTIONAL relative/absolute
 * rules. Optionality is modelled with {@link OptionalDouble} — never a sentinel like {@code -1}. If a
 * rule is absent the policy simply does not apply it; it never invents a threshold. All values are
 * expressed in the endpoint's own {@link RerankerScoreSemantics}.
 */
public final class RerankerSelectionConfiguration {

    /** Hard cap on how many top-ranked candidates are selected for navigation. Required, >= 1. */
    public final int maximumSelectedCandidates;
    /** Drop candidates whose score is below this absolute value, when present. */
    public final OptionalDouble absoluteMinimumScore;
    /** Require the best candidate to lead the runner-up by at least this margin, when present. */
    public final OptionalDouble minimumTopScoreMargin;
    /** Drop candidates that fall more than this far below the best score, when present. */
    public final OptionalDouble maximumScoreDropFromBest;

    public RerankerSelectionConfiguration(int maximumSelectedCandidates,
                                          OptionalDouble absoluteMinimumScore,
                                          OptionalDouble minimumTopScoreMargin,
                                          OptionalDouble maximumScoreDropFromBest) {
        this.maximumSelectedCandidates = maximumSelectedCandidates;
        this.absoluteMinimumScore = absoluteMinimumScore == null ? OptionalDouble.empty()
                : absoluteMinimumScore;
        this.minimumTopScoreMargin = minimumTopScoreMargin == null ? OptionalDouble.empty()
                : minimumTopScoreMargin;
        this.maximumScoreDropFromBest = maximumScoreDropFromBest == null ? OptionalDouble.empty()
                : maximumScoreDropFromBest;
    }

    /** Top-N only — no relative or absolute rules. */
    public static RerankerSelectionConfiguration topN(int maximumSelectedCandidates) {
        return new RerankerSelectionConfiguration(maximumSelectedCandidates, OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty());
    }
}
