package com.aresstack.askai.research.runtime.loop;

/** Status stream of a run (mapped to backend events by the caller). PHASE_READY is an EVENT, never a transition. */
public interface ResearchLoopListener {

    /** A technical diagnostic line — surfaced only in collapsible technical details, never as chat text. */
    void status(String message);

    /**
     * The run's counters changed or its current activity moved on. Callers render this as ONE in-place
     * progress card per run — never one bubble per page/source. {@code activity} carries the stable token
     * (SEARCHING/READING_PAGE/SOURCE_ACCEPTED/PAGE_SKIPPED) plus the structured context that makes the
     * browsing understandable: search query, final post-redirect URL/host and the page title.
     */
    void progress(ResearchRunProgress progress, ResearchRunActivity activity);

    /** The loop considers the phase ready for user review. The HOST decides what happens next. */
    void phaseReady(ResearchStopReason reason);

    /**
     * The user's attention is required (or no longer required) — e.g. a search engine demands a manual
     * CAPTCHA. {@code resolved=false} fires exactly once when the challenge appears, {@code resolved=true}
     * exactly once when it is gone. Never a technical error, never a run failure.
     */
    void attention(String reason, String domainFamily, String url, boolean resolved);
}
