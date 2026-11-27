package com.aresstack.askai.research.runtime.service;

import com.aresstack.askai.research.runtime.loop.ResearchStopReason;

/**
 * Classifies how a user-triggered (manual) web search TERMINATED, so the runtime can report it HONESTLY.
 *
 * <p>A TECHNICAL failure (the browser/SERP/reranker path broke, or the browser MCP endpoint was unreachable)
 * is a search the user can RETRY — it is NOT a "completed" search that merely found nothing. The distinction
 * matters for the host turn lifecycle: a {@code completed} manual search is bracketed by a post-search review
 * ({@code review_started → review_finished}) that the host relies on to release the composer; a technical
 * failure produced no sources to review, so routing it to the {@code failed} wire event instead guarantees the
 * host runs its TERMINAL path (composer released, persistent problem shown, browser stopped) exactly once —
 * never a chat turn wedged busy waiting for a review that will never come.</p>
 */
public final class ManualSearchOutcome {

    private ManualSearchOutcome() {
    }

    /**
     * Whether a terminal stop reason is a TECHNICAL failure (retry / fix configuration) rather than an honest
     * empty search ({@code NO_RELEVANT_PATHS} / {@code NO_SEMANTIC_MATCHES}) or a normal sufficiency/budget stop
     * that produced real sources. A technical failure must be surfaced as a {@code failed} manual search.
     */
    public static boolean isTechnicalFailure(ResearchStopReason reason) {
        if (reason == null) {
            return false;
        }
        switch (reason) {
            case SEARCH_TECHNICAL_PROBLEM:
            case MCP_UNAVAILABLE:
            case RERANKER_UNAVAILABLE:
            case RERANKER_INVALID_RESPONSE:
            case RERANKER_TIMEOUT:
            case RERANKER_CONFIGURATION_ERROR:
                return true;
            default:
                return false;
        }
    }
}
