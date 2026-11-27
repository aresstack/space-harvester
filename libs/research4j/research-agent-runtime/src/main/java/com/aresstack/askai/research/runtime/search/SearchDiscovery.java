package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The batch-wise discovery seam: one call, one batch, plus how to continue. It is {@link SearchStrategy}
 * with the ability to traverse a result set — and, exactly like it, responsible for NOTHING else: no
 * navigation, no page analysis, no acceptance, no session state.
 * <p>
 * Continuation lives HERE and nowhere else. The acquisition engine must never learn what "page 2" means for
 * a browser engine or an offset for an API provider; it asks for the next batch, and the provider knows how.
 * <p>
 * A strategy that cannot paginate is a perfectly good discovery of ONE batch — see
 * {@link SingleBatchDiscovery}. It reports no continuation, which ends traversal honestly instead of
 * pretending pages exist.
 */
public interface SearchDiscovery {

    /**
     * Fetch one batch. Must consult {@code budget} before every external call and honour
     * {@code cancellation}. Checked {@link ToolInvoker} exceptions come from the browser path only; API
     * providers signal failures through typed unchecked exceptions. There is never a silent fallback to a
     * different provider.
     */
    DiscoveryBatchResult discover(DiscoveryRequest request, CancellationSignal cancellation,
                                  SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;
}
