package com.aresstack.askai.browser;

/**
 * The typed outcome of ONE search attempt on ONE concrete search engine. Outcomes are per engine, not
 * global: a Bing attempt may be {@code CHALLENGE_PENDING} while the DuckDuckGo attempt of the SAME query
 * delivers {@code ORGANIC_RESULTS}. The engine-fallback policy decides from these outcomes — there is NO
 * flat all-anchors fallback in any case (hard invariant of the Legacy-Browser-Search requirements).
 */
public enum LegacySearchAttemptOutcome {

    /** Organic result candidates were extracted. */
    ORGANIC_RESULTS,
    /** The page was readable but no organic result link was recognized. */
    NO_ORGANIC_RESULTS,
    /** The result structure could not be resolved (reserved for the structural SERP analysis). */
    EXTRACTION_FAILED,
    /** A manual challenge (CAPTCHA) blocks this engine; the challenge is parked, the family locked. */
    CHALLENGE_PENDING,
    /** A consent wall could not be dismissed and the page stayed unreadable. */
    CONSENT_UNRESOLVED,
    /** Navigation to the engine failed (unreachable, blocked, policy). */
    NAVIGATION_FAILED,
    /**
     * The engine's start page loaded, but its search field could not be found or operated with real input (covered,
     * no focus). Never answered with a result URL instead (issue #22).
     */
    SEARCH_BOX_UNAVAILABLE,
    /** The search was cancelled; no further engine is tried. */
    CANCELLED
}
