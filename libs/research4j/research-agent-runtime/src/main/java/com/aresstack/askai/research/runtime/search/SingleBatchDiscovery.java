package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.Collections;

/**
 * Adapts an existing {@link SearchStrategy} to the batch-wise contract: it serves the FIRST batch and
 * reports no continuation, because neither the browser SERP path nor the current API providers can page
 * through a result set yet.
 * <p>
 * This is deliberately honest rather than clever. A traversal over this discovery ends with
 * "no continuation" after one batch instead of re-running the same query and pretending the identical hits
 * are a second page. When a provider gains real pagination, it implements {@link SearchDiscovery} directly
 * and every caller profits without changing.
 */
public final class SingleBatchDiscovery implements SearchDiscovery {

    private final SearchStrategy strategy;
    private final String provider;

    public SingleBatchDiscovery(SearchStrategy strategy, String provider) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        this.strategy = strategy;
        this.provider = provider == null ? "" : provider;
    }

    @Override
    public DiscoveryBatchResult discover(DiscoveryRequest request, CancellationSignal cancellation,
                                         SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        if (!request.isFirstBatch()) {
            // Asked for a continuation this strategy never handed out: answer with an empty batch instead
            // of repeating batch 1 under a new ordinal.
            return new DiscoveryBatchResult(Collections.<com.aresstack.askai.browser.search
                    .SearchResultCandidate>emptyList(), Collections.<String>emptyList(),
                    Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                    Collections.singletonList("provider cannot paginate"),
                    InitialSearchStatus.NO_RESULTS, provider, "");
        }
        return DiscoveryBatchResult.fromInitialSearch(
                strategy.search(request.toInitialSearchRequest(), cancellation, budget), provider);
    }
}
