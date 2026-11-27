package com.aresstack.askai.research.runtime.team;

/**
 * The post-manual-search review turn, extracted behind narrow seams so its contract is unit-testable:
 * the SUMMARY of the newly accepted sources is phase-INDEPENDENT (a search in RESEARCH deserves a
 * visible review just like one in SCOPING), while the scoping search-suggestion refresh remains a
 * scoping-only concern — {@code emitResult} (the productive {@code emitTeamAgentResult}) already
 * projects nothing outside scoping. A failed model turn is never silent: the user gets a neutral,
 * visible acknowledgement (the sources ARE saved) and the typed status goes to the technical log.
 */
public final class PostSearchReview {

    /**
     * The model turn — productive: {@code ResearchTeamAgent.reviewTurn}. NOT {@code respond}: a button
     * press is not a sentence the user wrote, and it must not enter the conversation as one.
     */
    public interface Model {
        TeamAgentResult internalTurn(String instruction, TeamAgentStateView view);
    }

    /** The three ways a review surfaces — productive: emitTeamAgentResult / plain message / wire log. */
    public interface Emitter {
        void emitResult(TeamAgentResult result, String phaseId);

        void emitVisible(String message);

        void emitLog(String line);
    }

    private PostSearchReview() {
    }

    public static com.aresstack.askai.research.domain.search.PostSearchReviewOutcome run(
            TeamAgentStateView view, Model model, Emitter emitter, String languageCode) {
        boolean scoping = "scoping".equalsIgnoreCase(view.getPhaseId());
        System.err.println("[manual-search] review state phase=" + view.getPhaseId()
                + " mode=" + (scoping ? "summary+suggestions" : "summary-only"));
        TeamAgentResult result = model.internalTurn(scoping
                ? TeamAgentPlaybook.sourceReviewInstruction()
                : TeamAgentPlaybook.sourceSummaryInstruction(), view);
        System.err.println("[manual-search] review result=" + result.getStatus());
        if (result.isOk()) {
            emitter.emitResult(result, view.getPhaseId());
            return com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.SUCCEEDED;
        }
        // No silent disappearance: the sources are saved either way, say so visibly.
        emitter.emitVisible(fallbackMessage(languageCode));
        emitter.emitLog("source review turn not ok: " + result.getStatus()
                + (result.getDetail() == null || result.getDetail().isEmpty()
                        ? "" : " (" + result.getDetail() + ")"));
        return com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.FAILED;
    }

    /**
     * The neutral visible acknowledgement when the review model turn failed. It does NOT call itself
     * "automatic": the user pressed "Neue Quellen auswerten", so what failed is the evaluation THEY asked
     * for — describing it as an automatism would deny their own action.
     */
    static String fallbackMessage(String languageCode) {
        if ("de".equalsIgnoreCase(languageCode)) {
            return "Die neuen Quellen wurden übernommen; die Auswertung konnte diesmal nicht "
                    + "erstellt werden.";
        }
        return "The new sources were saved; the evaluation could not be produced this time.";
    }
}
