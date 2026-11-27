package com.aresstack.askai.research.runtime.team;

/**
 * The assistant's advisory recommendation plus a short human reason. Advisory ONLY (see
 * {@link PhaseAdviceRecommendation}): it is displayed and logged, never acted on to change a phase.
 */
public final class PhaseAdvice {

    private final PhaseAdviceRecommendation recommendation;
    private final String reason;

    public PhaseAdvice(PhaseAdviceRecommendation recommendation, String reason) {
        this.recommendation = recommendation == null ? PhaseAdviceRecommendation.NEUTRAL : recommendation;
        this.reason = reason == null ? "" : reason.trim();
    }

    /** A neutral, reasonless advice — the default when the model offers none. */
    public static PhaseAdvice neutral() {
        return new PhaseAdvice(PhaseAdviceRecommendation.NEUTRAL, "");
    }

    public PhaseAdviceRecommendation getRecommendation() {
        return recommendation;
    }

    public String getReason() {
        return reason;
    }
}
