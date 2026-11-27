package com.aresstack.askai.research.runtime.search;

/**
 * How an INITIAL {@link SearchStrategy} search concluded, kept separate from the candidate list so the loop
 * can tell a genuinely empty search apart from a technical failure. Without this distinction a browser SERP
 * that could not be extracted (e.g. because the model-backed layout repair was unavailable, or every engine
 * was blocked with nothing extractable) produces zero candidates and is indistinguishable from an honest
 * "no results" — so a technical problem gets reported to the user as {@code NO_RELEVANT_PATHS}.
 */
public enum InitialSearchStatus {

    /** Usable organic candidates were produced (they still face the mandatory reranker before opening). */
    RESULTS,

    /** The search ran cleanly and there were genuinely no organic results — an honest empty search. */
    NO_RESULTS,

    /**
     * The search failed technically and produced no usable candidates: the SERP layout could not be
     * extracted (e.g. the layout-repair model was unavailable), or every engine was blocked with nothing
     * extractable. This is NOT an empty search — the loop surfaces it as a technical problem the user can
     * retry, never as {@code NO_RELEVANT_PATHS}.
     */
    TECHNICAL_PROBLEM
}
