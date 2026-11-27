package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;

/**
 * The deterministic baseline completion: the search is done exactly when the configured number of accepted
 * sources is reached. No host diversity, no minimums, no semantic judgement, no heuristics —
 * {@code accepted < target → continue; accepted >= target → complete}. Every other ending keeps its own
 * honest reason (inherited identity {@link #labelExhaustion}). The target comes from the run's configured
 * budget ({@code maxAcceptedSources}), never from an invented constant.
 */
public final class FixedAcceptedSourceCountPolicy implements SearchCompletionPolicy {

    private final int targetAcceptedSources;

    public FixedAcceptedSourceCountPolicy(int targetAcceptedSources) {
        if (targetAcceptedSources < 1) {
            throw new IllegalArgumentException(
                    "targetAcceptedSources must be >= 1, got " + targetAcceptedSources);
        }
        this.targetAcceptedSources = targetAcceptedSources;
    }

    public boolean isComplete(ResearchRunProgress progress) {
        return progress.getAcceptedSources() >= targetAcceptedSources;
    }
}
