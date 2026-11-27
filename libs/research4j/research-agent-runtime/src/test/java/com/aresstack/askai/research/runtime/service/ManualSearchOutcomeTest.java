package com.aresstack.askai.research.runtime.service;

import com.aresstack.askai.research.runtime.loop.ResearchStopReason;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The manual-search terminal classification: a TECHNICAL failure must be routed to a {@code failed} wire event
 * (so the host's terminal path runs and the chat turn never wedges busy), while an honest empty search or a
 * normal sufficiency/budget stop stays a {@code completed} search bracketed by its post-search review.
 */
public class ManualSearchOutcomeTest {

    @Test
    public void technicalStopsAreFailures() {
        assertTrue(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.SEARCH_TECHNICAL_PROBLEM));
        assertTrue(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.MCP_UNAVAILABLE));
        assertTrue(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.RERANKER_UNAVAILABLE));
        assertTrue(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.RERANKER_TIMEOUT));
        assertTrue(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.RERANKER_INVALID_RESPONSE));
        assertTrue(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.RERANKER_CONFIGURATION_ERROR));
    }

    @Test
    public void honestEmptyAndNormalStopsAreNotFailures() {
        assertFalse(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.NO_RELEVANT_PATHS));
        assertFalse(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.NO_SEMANTIC_MATCHES));
        assertFalse(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.SUFFICIENT_EVIDENCE));
        assertFalse(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.TIME_BUDGET_EXHAUSTED));
        assertFalse(ManualSearchOutcome.isTechnicalFailure(ResearchStopReason.USER_CANCELLED));
        assertFalse(ManualSearchOutcome.isTechnicalFailure(null));
    }

    /** Every technical reason yields a name() that maps to the host's user-readable technical failure text. */
    @Test
    public void technicalReasonsHaveStableNames() {
        assertTrue(ManualSearchOutcome.isTechnicalFailure(
                ResearchStopReason.valueOf(ResearchStopReason.SEARCH_TECHNICAL_PROBLEM.name())));
    }
}
