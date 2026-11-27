package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The single seam the research loop uses to obtain its INITIAL entry URLs. A strategy decides ONLY how a
 * query becomes initial URL candidates; it is not responsible for navigation, page analysis, link
 * discovery, capture, source acceptance, findings or session state. Once it returns, the loop's existing
 * Playwright exploration takes over unchanged.
 *
 * <p>The legacy browser SERP path is the {@link LegacyBrowserSearchStrategy} implementation; API-based
 * discovery is {@link SingleProviderSearchStrategy} over a
 * {@link com.aresstack.askai.research.runtime.search.provider.SearchProvider}. The interface carries no
 * Swing types and no concrete provider DTOs.</p>
 */
public interface SearchStrategy {

    /**
     * Resolve initial URL candidates for {@code request}. Must consult {@code budget} before every external
     * call and honour {@code cancellation}. The checked {@link ToolInvoker} exceptions are thrown only by
     * the legacy MCP path (the loop maps {@code EndpointUnavailable} to MCP_UNAVAILABLE); API providers
     * signal failures through typed unchecked provider exceptions instead — there is never a silent
     * fallback to a different strategy.
     */
    InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                               SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;
}
