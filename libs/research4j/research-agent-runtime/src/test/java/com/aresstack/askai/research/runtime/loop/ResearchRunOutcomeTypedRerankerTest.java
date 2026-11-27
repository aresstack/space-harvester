package com.aresstack.askai.research.runtime.loop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A5: the typed reranker stop reasons carry their OWN recommended action and recoverability — no
 * technical reranker failure ever degrades into the generic budget handling, and the semantic
 * NO_SEMANTIC_MATCHES behaves like "nothing relevant", never like a failure.
 */
public class ResearchRunOutcomeTypedRerankerTest {

    private static ResearchRunOutcome outcome(ResearchStopReason reason) {
        return ResearchRunOutcome.from(reason, new ResearchRunProgress(),
                ResearchRunBudget.defaults());
    }

    @Test
    public void transientRerankerFailuresRecommendRetryAndStayRecoverable() {
        for (ResearchStopReason reason : new ResearchStopReason[]{
                ResearchStopReason.RERANKER_UNAVAILABLE,
                ResearchStopReason.RERANKER_TIMEOUT,
                ResearchStopReason.RERANKER_INVALID_RESPONSE}) {
            ResearchRunOutcome o = outcome(reason);
            assertEquals(reason + " recommends a retry",
                    ResearchRunOutcome.RecommendedAction.RETRY, o.getRecommendedAction());
            assertTrue(reason + " is recoverable (retry after fixing the runtime)",
                    o.isRecoverable());
        }
    }

    @Test
    public void configurationErrorRecommendsTheConfigurationAndIsNotRecoverable() {
        ResearchRunOutcome o = outcome(ResearchStopReason.RERANKER_CONFIGURATION_ERROR);
        assertEquals(ResearchRunOutcome.RecommendedAction.OPEN_CONFIGURATION,
                o.getRecommendedAction());
        assertFalse("retrying cannot help until the snapshot/selection is fixed",
                o.isRecoverable());
    }

    @Test
    public void noSemanticMatchesBehavesLikeNoRelevantPathsNotLikeAFailure() {
        ResearchRunOutcome empty = outcome(ResearchStopReason.NO_SEMANTIC_MATCHES);
        assertEquals("without evidence: refine the scope",
                ResearchRunOutcome.RecommendedAction.REFINE_RESEARCH_SCOPE,
                empty.getRecommendedAction());
        assertTrue(empty.isRecoverable());

        ResearchRunProgress satisfied = new ResearchRunProgress();
        for (int i = 1; i <= 3; i++) {
            satisfied.pageVisited("https://host" + i + ".example/a", "host" + i + ".example");
            satisfied.sourceAccepted();
        }
        assertEquals("with sufficient evidence: review it",
                ResearchRunOutcome.RecommendedAction.REVIEW_EVIDENCE,
                ResearchRunOutcome.from(ResearchStopReason.NO_SEMANTIC_MATCHES, satisfied,
                        ResearchRunBudget.defaults()).getRecommendedAction());
    }

    @Test
    public void rerankerReasonsNeverCarryTheGenericBudgetActionSet() {
        for (ResearchStopReason reason : new ResearchStopReason[]{
                ResearchStopReason.RERANKER_UNAVAILABLE,
                ResearchStopReason.RERANKER_TIMEOUT,
                ResearchStopReason.RERANKER_INVALID_RESPONSE,
                ResearchStopReason.RERANKER_CONFIGURATION_ERROR}) {
            ResearchRunOutcome o = outcome(reason);
            assertFalse(reason + " must never look like a budget stop",
                    o.getRecommendedAction() == ResearchRunOutcome.RecommendedAction.CONTINUE_RESEARCH);
        }
    }
}
